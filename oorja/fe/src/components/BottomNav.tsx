import { Home, CalendarDays, CircleHelp, UserRound, SunMedium } from "lucide-react";
import { NavLink } from "react-router-dom";

export function BottomNav() {
  const items = [
    ["/", "Home", Home],
    ["/plants", "Plants", SunMedium],
    ["/bookings", "Bookings", CalendarDays],
    ["/help", "Help", CircleHelp],
    ["/profile", "Profile", UserRound]
  ] as const;
  return <nav className="bottom-nav">{items.map(([to, label, Icon]) =>
    <NavLink key={to} to={to} end={to === "/"}><Icon size={20}/><span>{label}</span></NavLink>
  )}</nav>;
}
