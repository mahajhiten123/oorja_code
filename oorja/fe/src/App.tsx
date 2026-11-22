import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AppShell } from "./components/AppShell";
import { BottomNav } from "./components/BottomNav";
import { AuthModal } from "./components/AuthModal";
import { Home } from "./pages/Home";
import { Dashboard } from "./pages/Dashboard";
import { Services } from "./pages/Services";
import { ServiceDetail } from "./pages/ServiceDetail";
import { Checkout } from "./pages/Checkout";
import { Bookings } from "./pages/Bookings";
import { Quotations } from "./pages/Quotations";
import { MyPlants } from "./pages/MyPlants";
import { PlantDetail } from "./pages/PlantDetail";
import { Amc } from "./pages/Amc";
import { Profile } from "./pages/Profile";
import { Help } from "./pages/Help";
import { Support } from "./pages/Support";
import { Invoices } from "./pages/Invoices";
import { Refunds } from "./pages/Refunds";
import { ServiceReports } from "./pages/ServiceReports";
import { useAuth } from "./auth/AuthContext";
import { AuthProvider } from "./auth/AuthContext";
import { AuthModalProvider } from "./auth/AuthModalContext";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { AdminAuthProvider } from "./admin/AdminAuthContext";
import { AdminProtectedRoute } from "./admin/AdminProtectedRoute";
import { AdminLayout } from "./admin/AdminLayout";
import { AdminLogin } from "./admin/pages/AdminLogin";
import { AdminOverview } from "./admin/pages/AdminOverview";
import { AdminMembers } from "./admin/pages/AdminMembers";
import { AdminBookings } from "./admin/pages/AdminBookings";
import { AdminQuotations } from "./admin/pages/AdminQuotations";
import { AdminTechnicians } from "./admin/pages/AdminTechnicians";
import { AdminRefunds } from "./admin/pages/AdminRefunds";
import { AdminPayments } from "./admin/pages/AdminPayments";
import { AdminSupport } from "./admin/pages/AdminSupport";
import { AdminServiceReports } from "./admin/pages/AdminServiceReports";
import "./styles.css";

/**
 * Authenticated visitors get a personalized dashboard instead of the public
 * marketing landing page — the whole point being that returning customers
 * land somewhere useful (their bookings/quotes/plants) rather than seeing
 * the same pitch every time.
 */
function HomeRoute() {
  const { isAuthenticated, loading } = useAuth();
  if (loading) {
    return <div className="page" style={{ display: "grid", placeItems: "center", minHeight: "60vh" }}><div className="pay-spinner" style={{ borderTopColor: "var(--green)", borderColor: "rgba(8,122,77,.25)" }} /></div>;
  }
  return isAuthenticated ? <Dashboard /> : <Home />;
}

export default function App() {
  return (
    <AuthProvider>
      <AdminAuthProvider>
        <AuthModalProvider>
          <BrowserRouter>
            <Routes>
              <Route path="/checkout" element={<ProtectedRoute><Checkout/></ProtectedRoute>}/>

              <Route path="/admin/login" element={<AdminLogin/>}/>
              <Route path="/admin" element={<AdminProtectedRoute><AdminLayout/></AdminProtectedRoute>}>
                <Route index element={<AdminOverview/>}/>
                <Route path="members" element={<AdminMembers/>}/>
                <Route path="bookings" element={<AdminBookings/>}/>
                <Route path="quotations" element={<AdminQuotations/>}/>
                <Route path="technicians" element={<AdminTechnicians/>}/>
                <Route path="refunds" element={<AdminRefunds/>}/>
                <Route path="payments" element={<AdminPayments/>}/>
                <Route path="support" element={<AdminSupport/>}/>
                <Route path="service-reports" element={<AdminServiceReports/>}/>
              </Route>

              <Route path="*" element={
                <AppShell>
                  <Routes>
                    <Route path="/" element={<HomeRoute/>}/>
                    <Route path="/services" element={<Services/>}/>
                    <Route path="/services/:id" element={<ServiceDetail/>}/>
                    <Route path="/bookings" element={<ProtectedRoute><Bookings/></ProtectedRoute>}/>
                    <Route path="/quotations" element={<ProtectedRoute><Quotations/></ProtectedRoute>}/>
                    <Route path="/plants" element={<ProtectedRoute><MyPlants/></ProtectedRoute>}/>
                    <Route path="/plants/:id" element={<ProtectedRoute><PlantDetail/></ProtectedRoute>}/>
                    <Route path="/amc" element={<Amc/>}/>
                    <Route path="/profile" element={<ProtectedRoute><Profile/></ProtectedRoute>}/>
                    <Route path="/help" element={<Help/>}/>
                    <Route path="/support" element={<ProtectedRoute><Support/></ProtectedRoute>}/>
                    <Route path="/invoices" element={<ProtectedRoute><Invoices/></ProtectedRoute>}/>
                    <Route path="/refunds" element={<ProtectedRoute><Refunds/></ProtectedRoute>}/>
                    <Route path="/service-reports" element={<ProtectedRoute><ServiceReports/></ProtectedRoute>}/>
                  </Routes>
                  <BottomNav/>
                </AppShell>
              }/>
            </Routes>
            <AuthModal/>
          </BrowserRouter>
        </AuthModalProvider>
      </AdminAuthProvider>
    </AuthProvider>
  );
}
