import { useEffect, useState } from "react";
import { getApiBaseUrl, getAuthToken, setApiBaseUrl, setAuthToken } from "../../api/client";

const THEME_KEY = "schemaMigrator.theme";

export const SettingsPage = () => {
  const [apiBase, setApiBase] = useState(getApiBaseUrl());
  const [token, setToken] = useState(getAuthToken());
  const [theme, setTheme] = useState(() => window.localStorage.getItem(THEME_KEY) || "light");
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
        <label>
          API base URL
          <input value={apiBase} onChange={(event) => setApiBase(event.target.value)} placeholder="/api" />
        </label>
        <label>
          Bearer token
          <input value={token} onChange={(event) => setToken(event.target.value)} type="password" autoComplete="off" />
        </label>
        <label>
          Theme
          <select value={theme} onChange={(event) => setTheme(event.target.value)}>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
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
