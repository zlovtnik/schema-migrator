import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import { SignInIcon } from "@phosphor-icons/react/dist/csr/SignIn";
import { getAuthToken } from "../../api/client";
import { DocumentTitle } from "../../components/DocumentTitle";
import { Icon } from "../../components/ui/Icon";
import { isKeycloakConfigured, loginWithCredentials } from "../../auth/keycloak";

export const LoginPage = () => {
  const [authenticated, setAuthenticated] = useState(Boolean(getAuthToken()));
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const configured = isKeycloakConfigured();

  useEffect(() => {
    setAuthenticated(Boolean(getAuthToken()));
  }, []);

  if (authenticated) {
    return <Navigate to="/overview" replace />;
  }

  const signIn = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!username.trim() || !password) {
      setError("Enter your username and password.");
      return;
    }

    setError(undefined);
    setSubmitting(true);
    loginWithCredentials(username.trim(), password)
      .then(() => setAuthenticated(true))
      .catch((nextError: unknown) => {
        setError(nextError instanceof Error ? nextError.message : "Sign-in failed.");
      })
      .finally(() => setSubmitting(false));
  };

  return (
    <main className="auth-shell">
      <DocumentTitle title="Sign in" />
      <section className="auth-panel" aria-labelledby="login-title">
        <div className="auth-brand">
          <div className="brand-mark auth-brand__mark" aria-hidden="true">
            <img src="/bedrock-logo.svg" alt="" />
          </div>
          <div>
            <span className="eyebrow">Bedrock</span>
            <h1 id="login-title">Schema Migrator</h1>
          </div>
        </div>

        <div className="auth-copy">
          <h2>Sign in</h2>
          <p>Access migration operations with your Bedrock identity.</p>
        </div>

        {!configured ? (
          <div className="status-banner status-banner--error" role="alert">
            Keycloak settings are missing.
          </div>
        ) : null}
        {error ? (
          <div className="status-banner status-banner--error" role="alert">
            {error}
          </div>
        ) : null}

        <form className="auth-form" onSubmit={signIn}>
          <label htmlFor="login-username">
            Username
            <input
              autoComplete="username"
              autoFocus
              id="login-username"
              name="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
          </label>
          <label htmlFor="login-password">
            Password
            <input
              autoComplete="current-password"
              id="login-password"
              name="password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <button className="button button--primary auth-button" disabled={!configured || submitting} type="submit">
            <Icon source={SignInIcon} size={20} weight="bold" />
            {submitting ? "Signing in" : "Sign in"}
          </button>
        </form>
      </section>
    </main>
  );
};
