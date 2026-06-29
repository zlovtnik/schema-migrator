import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { NavLink, Outlet } from "react-router-dom";
import { ClockCounterClockwiseIcon } from "@phosphor-icons/react/dist/csr/ClockCounterClockwise";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { GearIcon } from "@phosphor-icons/react/dist/csr/Gear";
import { ListIcon } from "@phosphor-icons/react/dist/csr/List";
import { ListBulletsIcon } from "@phosphor-icons/react/dist/csr/ListBullets";
import { ShieldCheckIcon } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { SidebarSimpleIcon } from "@phosphor-icons/react/dist/csr/SidebarSimple";
import { UploadSimpleIcon } from "@phosphor-icons/react/dist/csr/UploadSimple";
import { XIcon } from "@phosphor-icons/react/dist/csr/X";
import { ErrorGateBanner } from "../components/ErrorGateBanner";
import { ShortcutHelpDialog } from "../components/ShortcutHelpDialog";
import { TargetSelector } from "../components/TargetSelector";
import { Icon } from "../components/ui/Icon";
import { useErrorGate } from "../hooks/useErrorGate";

const SIDEBAR_KEY = "schemaMigrator.sidebarCollapsed";
const NAV_ID = "primary-navigation";

const navItems = [
  { to: "/overview", label: "Overview", icon: ShieldCheckIcon },
  { to: "/schema", label: "Schema", icon: DatabaseIcon },
  { to: "/targets", label: "Targets", icon: DatabaseIcon },
  { to: "/migrations", label: "Migrations", icon: UploadSimpleIcon },
  { to: "/runs", label: "Runs", icon: ClockCounterClockwiseIcon },
  { to: "/drift", label: "Drift", icon: ListBulletsIcon },
  { to: "/validation/latest", label: "Validation", icon: ShieldCheckIcon },
  { to: "/settings", label: "Settings", icon: GearIcon }
];

export const AppShell = () => {
  const [collapsed, setCollapsed] = useState(() => window.localStorage.getItem(SIDEBAR_KEY) === "true");
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [shortcutHelpOpen, setShortcutHelpOpen] = useState(false);
  const { failedRun } = useErrorGate();
  const queryClient = useQueryClient();

  useEffect(() => {
    window.localStorage.setItem(SIDEBAR_KEY, String(collapsed));
  }, [collapsed]);

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMobileMenuOpen(false);
        setShortcutHelpOpen(false);
        return;
      }

      if (shortcutHelpOpen) {
        return;
      }

      const modifier = event.metaKey || event.ctrlKey;
      if (!modifier && event.key === "/" && !isTextEntry(event.target)) {
        const filter = document.querySelector<HTMLElement>("[data-list-filter]");
        if (filter) {
          event.preventDefault();
          filter.focus();
        }
        return;
      }

      if (!modifier && event.key === "?" && !isTextEntry(event.target)) {
        event.preventDefault();
        setShortcutHelpOpen(true);
        return;
      }

      if (!modifier) {
        return;
      }

      if (event.key.toLowerCase() === "k") {
        event.preventDefault();
        setShortcutHelpOpen(true);
      }

      if (event.key === "\\") {
        event.preventDefault();
        setCollapsed((value) => !value);
      }

      if (event.key.toLowerCase() === "r") {
        event.preventDefault();
        void queryClient.invalidateQueries();
      }
    };

    window.addEventListener("keydown", handleShortcut);
    return () => window.removeEventListener("keydown", handleShortcut);
  }, [queryClient, shortcutHelpOpen]);

  const closeMobileMenu = () => setMobileMenuOpen(false);

  return (
    <>
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      {mobileMenuOpen ? (
        <button
          aria-label="Close navigation"
          className="mobile-menu-backdrop"
          type="button"
          onClick={closeMobileMenu}
        />
      ) : null}
      <div className={collapsed ? "app-shell app-shell--collapsed" : "app-shell"}>
        <header className="mobile-topbar">
          <div className="mobile-topbar__brand">
            <div className="brand-mark" aria-hidden="true">
              SM
            </div>
            <div className="brand-copy">
              <strong>Schema Migrator</strong>
              <span>Patch operations</span>
            </div>
          </div>
          <button
            aria-controls={NAV_ID}
            aria-expanded={mobileMenuOpen}
            aria-label={mobileMenuOpen ? "Close navigation" : "Open navigation"}
            className="mobile-menu-toggle"
            type="button"
            onClick={() => setMobileMenuOpen((value) => !value)}
          >
            <Icon source={mobileMenuOpen ? XIcon : ListIcon} size={20} weight="bold" />
          </button>
        </header>
        <aside className={mobileMenuOpen ? "sidebar sidebar--open" : "sidebar"} aria-label="Primary navigation">
          <div className="sidebar__brand">
            <div className="brand-mark" aria-hidden="true">
              SM
            </div>
            <div className="brand-copy">
              <strong>Schema Migrator</strong>
              <span>Patch operations</span>
            </div>
          </div>
          <nav className="sidebar__nav" id={NAV_ID}>
            {navItems.map((item) => {
              return (
                <NavLink
                  aria-label={item.label}
                  className="nav-link"
                  key={item.to}
                  title={collapsed ? item.label : undefined}
                  to={item.to}
                  onClick={closeMobileMenu}
                >
                  <Icon source={item.icon} size={20} />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
          <div className="sidebar__selector">
            <TargetSelector compact />
          </div>
          <button
            aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
            aria-pressed={collapsed}
            className="sidebar__collapse"
            type="button"
            onClick={() => setCollapsed((value) => !value)}
          >
            <Icon source={collapsed ? ListIcon : SidebarSimpleIcon} size={20} />
            <span>{collapsed ? "Expand" : "Collapse"}</span>
          </button>
        </aside>
        <div className="app-main">
          <ErrorGateBanner failedRun={failedRun} />
          <main className="main-scroll" id="main-content" tabIndex={-1}>
            <Outlet />
          </main>
        </div>
      </div>
      <ShortcutHelpDialog open={shortcutHelpOpen} onClose={() => setShortcutHelpOpen(false)} />
    </>
  );
};

const isTextEntry = (target: EventTarget | null): boolean => {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName.toLowerCase();
  return tag === "input" || tag === "textarea" || tag === "select" || target.isContentEditable;
};
