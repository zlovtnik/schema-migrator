import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { SignInIcon } from "@phosphor-icons/react/dist/csr/SignIn";
import { getAuthToken } from "../../api/client";
import { isKeycloakConfigured, loginWithKeycloak } from "../../auth/keycloak";
import { rememberPostAuthRedirect, takePostAuthRedirect } from "../../auth/postAuthRedirect";
import { DocumentTitle } from "../../components/DocumentTitle";
import { Icon } from "../../components/ui/Icon";

export const LoginPage = () => {
  const location = useLocation();
  const [authenticated, setAuthenticated] = useState(Boolean(getAuthToken()));
  const [redirectTo, setRedirectTo] = useState<string | undefined>(undefined);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const configured = isKeycloakConfigured();

  useEffect(() => {
    const hasToken = Boolean(getAuthToken());
    setAuthenticated(hasToken);
    if (hasToken) {
      setRedirectTo(takePostAuthRedirect(location.state));
    }
  }, [location.state]);

  if (authenticated && redirectTo) {
    return <Navigate to={redirectTo} replace />;
  }

  const signIn = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    setError(undefined);
    setSubmitting(true);
    rememberPostAuthRedirect(location.state);
    loginWithKeycloak()
      .then(() => {
        const hasToken = Boolean(getAuthToken());
        setAuthenticated(hasToken);
        if (hasToken) {
          setRedirectTo(takePostAuthRedirect(location.state));
        }
      })
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
          <button className="button button--primary auth-button" disabled={!configured || submitting} type="submit">
            <Icon source={SignInIcon} size={20} weight="bold" />
            {submitting ? "Signing in" : "Sign in"}
          </button>
        </form>
      </section>
    </main>
  );
};
