import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { ClipboardCheck, Database, History, Menu, PanelLeftClose, Settings, Upload } from "lucide-react";
import { ErrorGateBanner } from "../components/ErrorGateBanner";
import { TargetSelector } from "../components/TargetSelector";
import { useErrorGate } from "../hooks/useErrorGate";

const SIDEBAR_KEY = "schemaMigrator.sidebarCollapsed";

const navItems = [
  { to: "/targets", label: "Targets", icon: Database },
  { to: "/patches", label: "Patches", icon: Upload },
  { to: "/runs", label: "Runs", icon: History },
  { to: "/validation/latest", label: "Validation", icon: ClipboardCheck },
  { to: "/settings", label: "Settings", icon: Settings }
];

export const AppShell = () => {
  const [collapsed, setCollapsed] = useState(() => window.localStorage.getItem(SIDEBAR_KEY) === "true");
  const { failedRun } = useErrorGate();

  useEffect(() => {
    window.localStorage.setItem(SIDEBAR_KEY, String(collapsed));
  }, [collapsed]);

  return (
    <div className={collapsed ? "app-shell app-shell--collapsed" : "app-shell"}>
      <aside className="sidebar" aria-label="Primary navigation">
        <div className="sidebar__brand">
          <div className="brand-mark">SM</div>
          <div className="brand-copy">
            <strong>Schema Migrator</strong>
            <span>Patch operations</span>
          </div>
        </div>
        <nav className="sidebar__nav">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink className="nav-link" to={item.to} key={item.to}>
                <Icon size={17} aria-hidden="true" />
                <span>{item.label}</span>
              </NavLink>
            );
          })}
        </nav>
        <div className="sidebar__selector">
          <TargetSelector compact />
        </div>
        <button className="sidebar__collapse" type="button" onClick={() => setCollapsed((value) => !value)}>
          {collapsed ? <Menu size={17} /> : <PanelLeftClose size={17} />}
          <span>{collapsed ? "Expand" : "Collapse"}</span>
        </button>
      </aside>
      <div className="app-main">
        <ErrorGateBanner failedRun={failedRun} />
        <main className="main-scroll">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
