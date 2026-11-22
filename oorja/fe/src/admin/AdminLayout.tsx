import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { CalendarCheck2, ClipboardList, CreditCard, FileText, LayoutDashboard, LifeBuoy, LogOut, UserCog, Users, Wallet2 } from "lucide-react";
import { useAdminAuth } from "./AdminAuthContext";
import { Logo } from "../components/Logo";

const NAV = [
  { to: "/admin", label: "Overview", icon: LayoutDashboard, end: true },
  { to: "/admin/members", label: "Members", icon: Users },
  { to: "/admin/bookings", label: "Bookings", icon: CalendarCheck2 },
  { to: "/admin/quotations", label: "Quotations", icon: FileText },
  { to: "/admin/technicians", label: "Technicians", icon: UserCog },
  { to: "/admin/payments", label: "Payments", icon: CreditCard },
  { to: "/admin/refunds", label: "Refunds", icon: Wallet2 },
  { to: "/admin/service-reports", label: "Service Reports", icon: ClipboardList },
  { to: "/admin/support", label: "Support", icon: LifeBuoy }
] as const;

export function AdminLayout() {
  const { adminName, logout } = useAdminAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    // Await so the server-side revoke actually goes out before we navigate away.
    await logout();
    navigate("/admin/login", { replace: true });
  };

  const initials = (adminName || "Admin")
    .split(" ")
    .map(w => w[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();

  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <div className="admin-brand admin-brand-logo"><Logo compact /></div>
        <nav className="admin-nav">
          <span className="admin-nav-label">Console</span>
          {NAV.map(item => (
            <NavLink key={item.to} to={item.to} end={"end" in item ? item.end : false} className={({ isActive }) => isActive ? "active" : ""}>
              <item.icon size={17} /> {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="admin-sidebar-foot">
          <button className="admin-logout" onClick={handleLogout}><LogOut size={15} /> Logout</button>
        </div>
      </aside>
      <div className="admin-main">
        <header className="admin-topbar">
          <span className="admin-topbar-title">Admin console</span>
          <span className="admin-who">
            <span className="admin-who-avatar">{initials}</span>
            {adminName || "Admin"}
          </span>
        </header>
        <main className="admin-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
