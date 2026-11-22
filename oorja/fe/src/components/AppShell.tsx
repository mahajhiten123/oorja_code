import { Link, NavLink, useNavigate } from "react-router-dom";
import { Bell, CalendarDays, CheckCheck, ChevronDown, FileText, HelpCircle, LifeBuoy, LogOut, Sparkles, SunMedium, Ticket, UserRound, Wallet2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Logo } from "./Logo";
import { useAuth } from "../auth/AuthContext";
import { useAuthModal } from "../auth/AuthModalContext";
import { type AppNotification, getNotifications, getUnreadCount, markAllRead, relativeTime } from "../data/notificationsStore";

const NOTIF_ICON: Record<AppNotification["type"], typeof Bell> = {
  otp: Sparkles,
  login: UserRound,
  booking: Ticket,
  general: Bell
};

export function AppShell({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  const { user, isAuthenticated, logout } = useAuth();
  const { openLogin } = useAuthModal();
  const [menuOpen, setMenuOpen] = useState(false);
  const [notifOpen, setNotifOpen] = useState(false);
  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [unread, setUnread] = useState(0);

  // Refresh on mount and whenever the dropdown is toggled, so anything
  // added elsewhere (login, OTP sent, booking confirmed) shows up without
  // needing a live socket connection.
  useEffect(() => {
    const refresh = () => { setNotifications(getNotifications()); setUnread(getUnreadCount()); };
    refresh();
    // `storage` fires in other tabs; the custom event fires in this tab immediately
    // after every addNotification / markAllRead call.
    window.addEventListener("storage", refresh);
    window.addEventListener("oorja:notifications", refresh);
    const interval = window.setInterval(refresh, 4000);
    return () => {
      window.removeEventListener("storage", refresh);
      window.removeEventListener("oorja:notifications", refresh);
      window.clearInterval(interval);
    };
  }, []);

  const handleLogout = async () => {
    // Await so the server-side revoke actually goes out before we navigate away.
    await logout();
    setMenuOpen(false);
    navigate("/");
  };

  const toggleNotifications = () => {
    setNotifOpen(v => {
      const next = !v;
      if (next) { setNotifications(getNotifications()); setUnread(getUnreadCount()); }
      return next;
    });
  };

  const handleMarkAllRead = () => {
    markAllRead();
    setNotifications(getNotifications());
    setUnread(0);
  };

  return (
    <div className="app-shell">
      <header className="topbar">
        <Link to="/" className="logo-link"><Logo /></Link>
        <nav className="desktop-nav">
          <NavLink to="/" end>Home</NavLink>
          <NavLink to="/services">Services</NavLink>
          {isAuthenticated && <NavLink to="/plants">My Plants</NavLink>}
          {isAuthenticated && <NavLink to="/bookings">Bookings</NavLink>}
          {isAuthenticated && <NavLink to="/quotations">Quotations</NavLink>}
          <NavLink to="/amc">AMC Plans</NavLink>
          <a href="#contact" onClick={(e) => {
            if (location.pathname !== "/") { e.preventDefault(); navigate("/#contact"); }
          }}>Contact Us</a>
        </nav>
        <div className="top-actions">
          <div className="account-menu">
            <button className="icon-button" aria-label="Notifications" onClick={toggleNotifications}>
              <Bell size={20}/>{unread > 0 && <span className="notification-dot"/>}
            </button>
            {notifOpen && (
              <div className="account-dropdown notif-dropdown" onMouseLeave={() => setNotifOpen(false)}>
                <div className="notif-head">
                  <b>Notifications</b>
                  {unread > 0 && <button className="text-link" onClick={handleMarkAllRead}><CheckCheck size={14}/> Mark all read</button>}
                </div>
                {notifications.length === 0 && <p className="notif-empty">No notifications yet. OTPs, logins and bookings will show up here.</p>}
                {notifications.map(n => {
                  const Icon = NOTIF_ICON[n.type];
                  return (
                    <div className={`notif-row ${n.read ? "" : "unread"}`} key={n.id}>
                      <div className="notif-icon"><Icon size={15}/></div>
                      <div>
                        <b>{n.title}</b>
                        <span>{n.message}</span>
                        <small>{relativeTime(n.createdAt)}</small>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {isAuthenticated ? (
            <div className="account-menu">
              <button className="profile-pill" onClick={() => setMenuOpen(v => !v)}>
                <UserRound size={18}/><span>{user?.name?.split(" ")[0] ?? "Account"}</span><ChevronDown size={14}/>
              </button>
              {menuOpen && (
                <div className="account-dropdown" onMouseLeave={() => setMenuOpen(false)}>
                  <Link to="/profile" onClick={() => setMenuOpen(false)}><UserRound size={16}/> My Profile</Link>
                  <Link to="/bookings" onClick={() => setMenuOpen(false)}><CalendarDays size={16}/> My Bookings</Link>
                  <Link to="/quotations" onClick={() => setMenuOpen(false)}><FileText size={16}/> My Quotations</Link>
                  <Link to="/plants" onClick={() => setMenuOpen(false)}><SunMedium size={16}/> My Plants</Link>
                  <Link to="/invoices" onClick={() => setMenuOpen(false)}><FileText size={16}/> Invoices</Link>
                  <Link to="/refunds" onClick={() => setMenuOpen(false)}><Wallet2 size={16}/> Refunds</Link>
                  <Link to="/support" onClick={() => setMenuOpen(false)}><LifeBuoy size={16}/> Support Tickets</Link>
                  <Link to="/help" onClick={() => setMenuOpen(false)}><HelpCircle size={16}/> Help &amp; Support</Link>
                  <div className="divider"/>
                  <button className="danger" onClick={handleLogout}><LogOut size={16}/> Logout</button>
                </div>
              )}
            </div>
          ) : (
            <button className="login-btn" onClick={openLogin}>
              <UserRound size={16}/> Login / Sign Up
            </button>
          )}
        </div>
      </header>
      <main>{children}</main>
      <footer className="footer" id="contact">
        <div className="footer-grid">
          <div><Logo/><p>Precision solar installation and maintenance for your assets.</p></div>
          <div><h4>Services</h4><Link to="/services/installation">New Installation</Link><Link to="/services/maintenance">Preventive Maintenance</Link><Link to="/services/cleaning">Plant Cleaning</Link><Link to="/services">View all services</Link></div>
          <div><h4>Customer</h4><Link to="/bookings">My Bookings</Link><Link to="/profile">Profile</Link>{!isAuthenticated && <button className="footer-link-btn" onClick={openLogin}>Sign in</button>}</div>
          <div><h4>Support</h4><p>Mon–Sat · 9:00 AM–7:00 PM</p><p>info@oorjaonmove.com</p></div>
        </div>
        <div className="footer-bottom">© 2026 Oorja on Move. All rights reserved.</div>
      </footer>
    </div>
  );
}
