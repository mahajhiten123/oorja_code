#!/usr/bin/env python3
"""
Database-level concurrency verification for the booking / payment invariants.

It replays, statement for statement, what SlotService / PaymentService / BookingService run inside their
transactions (the same UPDATE ... WHERE booked_count < capacity compare-and-swap, the same
SELECT ... FOR UPDATE on the payment row, the same unique indexes), from many simultaneous connections,
against a database that has all Flyway migrations (V1..V21) applied. It proves the DATABASE half of the
guarantees independent of Spring. The Spring half is covered by the JUnit tests in src/test.

    pip install psycopg2-binary
    PGDSN="host=/tmp port=5544 user=postgres dbname=oorja" python3 verify/db_concurrency_check.py
"""
import os, sys, threading, datetime as dt, uuid
import psycopg2
from psycopg2 import errors

DSN = os.environ.get("PGDSN", "host=localhost port=5432 user=postgres dbname=oorja")
FAILED = []

def conn():
    c = psycopg2.connect(DSN); c.autocommit = False; return c

def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (f"   [{detail}]" if detail else ""))
    if not ok: FAILED.append(name)

def run_parallel(n, fn):
    barrier = threading.Barrier(n); out = [None] * n
    def w(i):
        c = conn()
        try:
            barrier.wait(); out[i] = fn(i, c)
        except Exception as e:
            out[i] = ("ERR", repr(e))
        finally:
            c.close()
    ts = [threading.Thread(target=w, args=(i,)) for i in range(n)]
    [t.start() for t in ts]; [t.join() for t in ts]
    return out

def one(c, sql, args=()):
    cur = c.cursor(); cur.execute(sql, args); return cur

# ---- fixtures -------------------------------------------------------------------------------------------
def fixture():
    c = conn(); u = uuid.uuid4().hex[:8]
    cid = one(c, "insert into customers(customer_code,name,email) values (%s,'T',%s) returning id", (f"C{u}", f"{u}@t.io")).fetchone()[0]
    pid = one(c, "insert into plants(plant_code,name,type,capacity_kw,location,customer_id) values (%s,'P','RESIDENTIAL',5,'x',%s) returning id", (f"P{u}", cid)).fetchone()[0]
    c.commit(); c.close(); return cid, pid

def new_payment(c, cid, slot_date, slot_time, hold="HELD", status="CREATED", expires_min=30):
    u = uuid.uuid4().hex[:10]
    return one(c, """insert into payments(payment_number,customer_id,amount_paise,currency,status,razorpay_order_id,
                       draft_scheduled_date,draft_scheduled_time,slot_hold_state,slot_hold_expires_at,razorpay_payment_id)
                     values (%s,%s,50000,'INR',%s,%s,%s,%s,%s, now() + (%s || ' minutes')::interval, %s) returning id""",
              (f"PAY{u}", cid, status, f"order_{u}", slot_date, slot_time, hold, str(expires_min),
               f"pay_{u}" if status == "CAPTURED" else None)).fetchone()[0]

def claim(c, d, t, capacity):
    """SlotService.reserve: createIfAbsent + tryClaimSeat (+ retry), same SQL as BookingSlotRepository."""
    one(c, "insert into booking_slots(slot_date,slot_time,capacity,booked_count,created_at,updated_at) values (%s,%s,%s,0,now(),now()) on conflict (slot_date,slot_time) do nothing", (d, t, capacity))
    cur = one(c, "update booking_slots set booked_count=booked_count+1 where slot_date=%s and slot_time=%s and booked_count<capacity", (d, t))
    return cur.rowcount == 1

def booked(d, t):
    c = conn(); r = one(c, "select booked_count from booking_slots where slot_date=%s and slot_time=%s", (d, t)).fetchone(); c.close()
    return r[0] if r else 0

import random
# A random far-future date per run, so re-running never collides with slots a previous run filled.
DAY = dt.date(2100, 1, 1) + dt.timedelta(days=random.randint(0, 2_000_000 // 365 * 300))

# ---- 1. two customers, last seat ------------------------------------------------------------------------
def s1():
    t = dt.time(9, 0); cap = 4
    c = conn(); claim(c, DAY, t, cap); c.commit()
    for _ in range(cap - 2):  # 1 claimed above + (cap-2) more = cap-1 taken, exactly one seat left
        claim(c, DAY, t, cap); c.commit()
    c.close()
    def worker(i, c):
        ok = claim(c, DAY, t, cap)
        if ok: c.commit()
        else: c.rollback()
        return ok
    res = run_parallel(2, worker)
    check("1. two customers race for the LAST seat: exactly one wins", sum(r is True for r in res) == 1, f"results={res}")
    check("1. counter == capacity (never overbooked)", booked(DAY, t) == cap, f"booked={booked(DAY,t)}")

# ---- 2. never-touched slot, big stampede ----------------------------------------------------------------
def s2():
    t = dt.time(10, 0); cap = 4
    def worker(i, c):
        ok = claim(c, DAY, t, cap)
        (c.commit if ok else c.rollback)(); return ok
    res = run_parallel(40, worker)
    check("2. 40 simultaneous claims on a brand-new slot: exactly capacity (4) win",
          sum(r is True for r in res) == cap and not any(isinstance(r, tuple) for r in res), f"wins={sum(r is True for r in res)} errs={[r for r in res if isinstance(r,tuple)][:2]}")
    check("2. counter == capacity", booked(DAY, t) == cap)

# ---- 3. checkout holds: capacity enforced BEFORE money moves --------------------------------------------
def s3():
    t = dt.time(11, 0); cap = 3
    custs = [fixture()[0] for _ in range(12)]
    def worker(i, c):          # == PaymentService.createOrder's short tx: claim seat + insert payment
        if not claim(c, DAY, t, cap): c.rollback(); return False
        new_payment(c, custs[i], DAY, t); c.commit(); return True
    res = run_parallel(12, worker)
    c = conn(); held = one(c, "select count(*) from payments where draft_scheduled_date=%s and draft_scheduled_time=%s and slot_hold_state='HELD'", (DAY, t)).fetchone()[0]; c.close()
    check("3. 12 customers start checkout for a 3-seat slot: exactly 3 get an order", sum(r is True for r in res) == cap and held == cap, f"wins={sum(r is True for r in res)} held_rows={held}")
    check("3. counter == #HELD payments", booked(DAY, t) == held)

# ---- 4. double-click Pay: one booking per payment -------------------------------------------------------
def s4():
    t = dt.time(12, 0); cap = 4
    cid, pid_plant = fixture()
    c = conn(); claim(c, DAY, t, cap); pay = new_payment(c, cid, DAY, t, status="CAPTURED"); c.commit(); c.close()
    def worker(i, c):   # == BookingService.createForCustomer paid path
        row = one(c, "select booking_id, slot_hold_state from payments where id=%s for update", (pay,)).fetchone()
        if row[0] is not None:                       # already spent -> replay, create nothing
            c.rollback(); return "replayed"
        one(c, "update payments set slot_hold_state='CONSUMED', slot_hold_expires_at=null where id=%s", (pay,))
        bid = one(c, """insert into bookings(token,customer_id,plant_id,service_type,scheduled_date,scheduled_time,status,slot_reserved,payment_ref,amount_paise)
                        values (%s,%s,%s,'Cleaning',%s,%s,'PENDING',true,%s,50000) returning id""", (uuid.uuid4().hex[:12], cid, pid_plant, DAY, t, f"pay_{pay}")).fetchone()[0]
        one(c, "update payments set booking_id=%s where id=%s", (bid, pay)); c.commit(); return "created"
    res = run_parallel(20, worker)
    c = conn(); n = one(c, "select count(*) from bookings where customer_id=%s", (cid,)).fetchone()[0]; c.close()
    check("4. 20 simultaneous 'Pay'/create-booking calls for one payment: exactly 1 booking", n == 1 and res.count("created") == 1, f"created={res.count('created')} replayed={res.count('replayed')} rows={n}")
    check("4. paid booking took over the hold: counter still 1 (no double-count)", booked(DAY, t) == 1, f"booked={booked(DAY,t)}")

# ---- 5. DB backstop: even a buggy caller can't double-spend ---------------------------------------------
def s5():
    cid, pl = fixture(); t = dt.time(13, 0)
    c = conn(); pay = new_payment(c, cid, DAY, t, hold="NONE", status="CAPTURED")
    ids = []; ref = 'ref_' + uuid.uuid4().hex
    for i in range(2):
        try:
            c2 = conn()
            bid = one(c2, "insert into bookings(token,customer_id,plant_id,service_type,scheduled_date,scheduled_time,status,payment_ref) values (%s,%s,%s,'X',%s,%s,'PENDING',%s) returning id", (uuid.uuid4().hex[:12], cid, pl, DAY, t, ref)).fetchone()[0]
            c2.commit(); ids.append(bid)
        except errors.UniqueViolation:
            c2.rollback(); ids.append(None)
        finally: c2.close()
    c.commit(); c.close()
    check("5. unique index uq_bookings_payment_ref rejects a 2nd booking for the same payment", ids[0] is not None and ids[1] is None)

# ---- 6. expiry sweep vs capture race --------------------------------------------------------------------
def s6():
    t = dt.time(14, 0); cap = 4; cid, _ = fixture()
    c = conn(); claim(c, DAY, t, cap); pay = new_payment(c, cid, DAY, t, expires_min=-5); c.commit(); c.close()   # lapsed hold
    def worker(i, c):
        row = one(c, "select status, slot_hold_state, slot_hold_expires_at < now() from payments where id=%s for update", (pay,)).fetchone()
        if i % 2 == 0:   # capture (verify / webhook)
            if row[0] in ("CAPTURED",): c.rollback(); return "noop"
            one(c, "update payments set status='CAPTURED', razorpay_payment_id=%s, captured_at=now() where id=%s", (f"pay_{uuid.uuid4().hex[:10]}", pay)); c.commit(); return "captured"
        # expiry sweep: releaseHoldIfExpired re-checks under the lock
        if row[1] != "HELD" or row[0] == "CAPTURED" or not row[2]: c.rollback(); return "skipped"
        one(c, "update booking_slots set booked_count=case when booked_count>0 then booked_count-1 else 0 end where slot_date=%s and slot_time=%s", (DAY, t))
        one(c, "update payments set slot_hold_state='RELEASED', slot_hold_expires_at=null where id=%s", (pay,)); c.commit(); return "released"
    res = run_parallel(10, worker)
    c = conn(); st, hs = one(c, "select status, slot_hold_state from payments where id=%s", (pay,)).fetchone(); c.close()
    seat_ok = (hs == "HELD" and booked(DAY, t) == 1) or (hs == "RELEASED" and st == "CAPTURED" and booked(DAY, t) == 0) or (hs == "RELEASED" and st != "CAPTURED" and booked(DAY, t) == 0)
    # The only illegal outcome would be: captured money AND seat released *after* capture. The lock + re-check forbids the
    # sweep from releasing once status=CAPTURED, so RELEASED+CAPTURED can only arise if release won the race first (then the
    # booking path re-claims; see BookingService.claimSeatForPaidBooking). Assert the seat count is never negative/double released.
    check("6. sweep vs capture race: seat released at most once, counter consistent", res.count("released") <= 1 and booked(DAY, t) in (0, 1) and seat_ok, f"released={res.count('released')} final=({st},{hs}) booked={booked(DAY,t)}")

# ---- 7. one open order per quotation --------------------------------------------------------------------
def s7():
    c = conn(); d = one(c, "select indexdef from pg_indexes where indexname='uq_payments_quotation_open'").fetchone(); c.close()
    check("7. uq_payments_quotation_open exists (one open order per quotation)", d is not None and "CREATED" in d[0])

# ---- 8. constraints reject impossible states ------------------------------------------------------------
def s8():
    cid, _ = fixture(); c = conn(); bad = 0
    for sql in ["update booking_slots set booked_count=-1 where id=(select min(id) from booking_slots)",
                "insert into payments(payment_number,customer_id,amount_paise,status,razorpay_order_id) values ('x1',%s,0,'CREATED','o_x1')",
                "insert into payments(payment_number,customer_id,amount_paise,status,razorpay_order_id) values ('x2',%s,10,'CAPTURED','o_x2')",   # captured w/o gateway id
                "insert into payments(payment_number,customer_id,amount_paise,status,razorpay_order_id,slot_hold_state) values ('x3',%s,10,'CREATED','o_x3','HELD')"]:  # HELD w/o slot
        try:
            cur = c.cursor(); cur.execute(sql, (cid,) if "%s" in sql else ()); c.commit()
        except (errors.CheckViolation,): bad += 1; c.rollback()
        except Exception as e: c.rollback(); print("   unexpected", repr(e))
    c.close(); check("8. CHECK constraints reject negative counts, 0-amount, captured-without-gateway-id, HELD-without-slot", bad == 4, f"rejected={bad}/4")

if __name__ == "__main__":
    for f in (s1, s2, s3, s4, s5, s6, s7, s8): f()
    print("\n" + ("ALL DATABASE INVARIANTS HELD" if not FAILED else f"{len(FAILED)} FAILED: {FAILED}"))
    sys.exit(1 if FAILED else 0)
