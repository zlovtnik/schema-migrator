import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getApiBaseUrl, setApiBaseUrl } from "../../api/client";
import { StatusBadge } from "../../components/StatusBadge";
import { useTargets } from "../../hooks/useTargets";

export const SettingsPage = () => {
  const queryClient = useQueryClient();
  const { data: targets = [], isLoading: targetsLoading, error: targetsError } = useTargets();
  const [apiBase, setApiBase] = useState(getApiBaseUrl());
  const [saved, setSaved] = useState(false);
  const productionTargets = targets.filter((target) => target.env === "production").length;

  const save = () => {
    setApiBaseUrl(apiBase);
    queryClient.clear();
    setSaved(true);
    window.setTimeout(() => setSaved(false), 1800);
  };

  return (
    <section className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Settings</span>
          <h1>Application config</h1>
        </div>
      </header>

      <div className="settings-layout">
        <nav className="settings-nav" aria-label="Settings sections">
          <a className="settings-nav__item" href="#application-settings">
            Application
          </a>
          <a className="settings-nav__item" href="#target-settings">
            Database targets
          </a>
        </nav>

        <div className="settings-content">
          <section className="settings-panel" id="application-settings">
            <div className="settings-panel__header">
              <div>
                <span className="eyebrow">Application</span>
                <h2>Client config</h2>
              </div>
            </div>

            <div className="settings-form">
              <label htmlFor="settings-api-base">
                API base URL
                <input
                  autoComplete="off"
                  id="settings-api-base"
                  name="api-base-url"
                  value={apiBase}
                  onChange={(event) => setApiBase(event.target.value)}
                  placeholder="/api"
                />
              </label>
              <div className="form-actions">
                <button className="button button--primary" type="button" onClick={save}>
                  Save settings
                </button>
                {saved ? <span className="inline-result inline-result--ok">Saved</span> : null}
              </div>
            </div>
          </section>

          <section className="settings-panel" id="target-settings">
            <div className="settings-panel__header">
              <div>
                <span className="eyebrow">Targets</span>
                <h2>Database targets</h2>
              </div>
              <div className="row-actions">
                <Link className="button button--secondary" to="/targets">
                  Manage
                </Link>
                <Link className="button button--primary" to="/targets?create=1">
                  Create
                </Link>
              </div>
            </div>

            <div className="settings-target-summary">
              <div>
                <span className="field-label">Total</span>
                <strong>{targetsLoading ? "..." : targets.length}</strong>
              </div>
              <div>
                <span className="field-label">Production</span>
                <strong>{targetsLoading ? "..." : productionTargets}</strong>
              </div>
            </div>

            {targetsError ? (
              <div className="status-banner status-banner--error">
                Unable to load targets.
              </div>
            ) : null}

            {!targetsError && !targetsLoading && targets.length === 0 ? (
              <div className="empty-state">No targets configured.</div>
            ) : null}

            {!targetsError && targets.length > 0 ? (
              <div className="settings-target-list">
                {targets.slice(0, 4).map((target) => (
                  <Link className="settings-target-row" key={target.id} to={`/targets/${target.id}`}>
                    <span>
                      <strong>{target.label}</strong>
                      <span>{target.app_name}</span>
                    </span>
                    <StatusBadge status={target.env === "production" ? "warning" : "clean"} title={target.env} />
                  </Link>
                ))}
              </div>
            ) : null}
          </section>
        </div>
      </div>
    </section>
  );
};
