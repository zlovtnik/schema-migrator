import { useEffect, useState } from "react";
import { getApiBaseUrl, getAuthToken, setApiBaseUrl, setAuthToken } from "../../api/client";

const THEME_KEY = "schemaMigrator.theme";

export const SettingsPage = () => {
  const [apiBase, setApiBase] = useState(getApiBaseUrl());
  const [token, setToken] = useState(getAuthToken());
  const [theme, setTheme] = useState(() => window.localStorage.getItem(THEME_KEY) || "dark");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    window.localStorage.setItem(THEME_KEY, theme);
  }, [theme]);

  const save = () => {
    setApiBaseUrl(apiBase);
    setAuthToken(token);
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
        <label htmlFor="settings-bearer-token">
          Bearer token
          <input
            autoComplete="current-password"
            id="settings-bearer-token"
            name="bearer-token"
            value={token}
            onChange={(event) => setToken(event.target.value)}
            type="password"
          />
        </label>
        <label htmlFor="settings-theme">
          Theme
          <select id="settings-theme" name="theme" value={theme} onChange={(event) => setTheme(event.target.value)}>
            <option value="dark">Dark</option>
            <option value="light">Light</option>
          </select>
        </label>
        <div className="form-actions">
          <button className="button button--primary" type="button" onClick={save}>
            Save settings
          </button>
          {saved ? <span className="inline-result inline-result--ok">Saved</span> : null}
        </div>
      </div>
    </section>
  );
};
