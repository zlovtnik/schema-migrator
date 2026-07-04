import { useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { NavLink, Outlet, useMatches } from "react-router-dom";
import { ClipboardTextIcon } from "@phosphor-icons/react/dist/csr/ClipboardText";
import { ClockCounterClockwiseIcon } from "@phosphor-icons/react/dist/csr/ClockCounterClockwise";
import { DatabaseIcon } from "@phosphor-icons/react/dist/csr/Database";
import { GearIcon } from "@phosphor-icons/react/dist/csr/Gear";
import { GitBranchIcon } from "@phosphor-icons/react/dist/csr/GitBranch";
import { ListIcon } from "@phosphor-icons/react/dist/csr/List";
import { ListBulletsIcon } from "@phosphor-icons/react/dist/csr/ListBullets";
import { ShieldCheckIcon } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { SidebarSimpleIcon } from "@phosphor-icons/react/dist/csr/SidebarSimple";
import { UploadSimpleIcon } from "@phosphor-icons/react/dist/csr/UploadSimple";
import { XIcon } from "@phosphor-icons/react/dist/csr/X";
import { AppBreadcrumbs } from "../components/AppBreadcrumbs";
import { DocumentTitle } from "../components/DocumentTitle";
import { activeRouteTitle } from "../components/breadcrumbs";
import { ErrorGateBanner } from "../components/ErrorGateBanner";
import { ShortcutHelpDialog } from "../components/ShortcutHelpDialog";
import { TargetSelector } from "../components/TargetSelector";
import { Icon } from "../components/ui/Icon";
import { useErrorGate } from "../hooks/useErrorGate";
import { useSession } from "../hooks/useSession";

const SIDEBAR_KEY = "schemaMigrator.sidebarCollapsed";
const NAV_ID = "primary-navigation";

const baseNavSections = [
  {
    label: "Operate",
    items: [
      { to: "/overview", label: "Overview", icon: ShieldCheckIcon },
      { to: "/schema", label: "Schema", icon: DatabaseIcon },
      { to: "/migrations", label: "Migrations", icon: UploadSimpleIcon },
      { to: "/snapshots", label: "Snapshots", icon: GitBranchIcon }
    ]
  },
  {
    label: "Observe",
    items: [
      { to: "/runs", label: "Runs", icon: ClockCounterClockwiseIcon },
      { to: "/drift", label: "Drift", icon: ListBulletsIcon },
      { to: "/validation/latest", label: "Validation", icon: ShieldCheckIcon }
    ]
  },
  {
    label: "Settings",
    items: [
      { to: "/settings", label: "Application", icon: GearIcon, end: true },
      { to: "/settings/targets", label: "Targets", icon: DatabaseIcon }
    ]
  }
];

export const AppShell = () => {
  const [collapsed, setCollapsed] = useState(() => window.localStorage.getItem(SIDEBAR_KEY) === "true");
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [shortcutHelpOpen, setShortcutHelpOpen] = useState(false);
  const { failedRun } = useErrorGate();
  const { canViewAudit, role, subject } = useSession();
  const queryClient = useQueryClient();
  const matches = useMatches();
  const routeTitle = activeRouteTitle(matches);
  const navSections = useMemo(
    () =>
      canViewAudit
        ? [
            ...baseNavSections,
            {
              label: "Admin",
              items: [{ to: "/audit", label: "Audit", icon: ClipboardTextIcon }]
            }
          ]
        : baseNavSections,
    [canViewAudit]
  );

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

      if (event.key.toLowerCase() === "k" && !isTextEntry(event.target)) {
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
      <DocumentTitle title={routeTitle} />
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
            <BrandIdentity />
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
            <BrandIdentity />
          </div>
          <nav className="sidebar__nav" id={NAV_ID}>
            {navSections.map((section) => (
              <div className="nav-section" key={section.label}>
                <div className="nav-section__label">{section.label}</div>
                {section.items.map((item) => (
                  <NavLink
                    aria-label={item.label}
                    className="nav-link"
                    end={item.end === true}
                    key={item.to}
                    title={collapsed ? item.label : undefined}
                    to={item.to}
                    onClick={closeMobileMenu}
                  >
                    <Icon source={item.icon} size={20} />
                    <span>{item.label}</span>
                  </NavLink>
                ))}
              </div>
            ))}
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
          <header className="app-topbar" aria-label="Session context">
            <div className="app-topbar__spacer" />
            <div className="session-context">
              {subject ? <span className="session-context__subject" title={subject}>{subject}</span> : null}
              <div className={`role-badge role-badge--${role}`} title={`Role: ${role}`}>
                <span>Role</span>
                <strong>{role}</strong>
              </div>
            </div>
          </header>
          <ErrorGateBanner failedRun={failedRun} />
          <main className="main-scroll" id="main-content" tabIndex={-1}>
            <AppBreadcrumbs />
            <Outlet />
          </main>
        </div>
      </div>
      <ShortcutHelpDialog open={shortcutHelpOpen} onClose={() => setShortcutHelpOpen(false)} />
    </>
  );
};

const BrandIdentity = () => (
  <>
    <div className="brand-mark" aria-hidden="true">
      <img src="/bedrock-logo.svg" alt="" />
    </div>
    <div className="brand-copy">
      <strong>Bedrock Schema Migrator</strong>
      <span>Patch operations</span>
    </div>
  </>
);

const isTextEntry = (target: EventTarget | null): boolean => {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tag = target.tagName.toLowerCase();
  return tag === "input" || tag === "textarea" || tag === "select" || target.isContentEditable;
};
