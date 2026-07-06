import { useEffect, useState } from "react";
import { Link, Navigate, useLocation } from "react-router-dom";
import { initKeycloak } from "../../auth/keycloak";
import { takePostAuthRedirect } from "../../auth/postAuthRedirect";
import { DocumentTitle } from "../../components/DocumentTitle";

export const CallbackPage = () => {
  const location = useLocation();
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const [redirectTo, setRedirectTo] = useState("/overview");

  useEffect(() => {
    let active = true;

    initKeycloak()
      .then((authenticated) => {
        if (!active) {
          return;
        }
        if (authenticated) {
          setRedirectTo(takePostAuthRedirect(location.state));
          setReady(true);
        } else {
          setFailed(true);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    return () => {
      active = false;
    };
  }, [location.state]);

  if (ready) {
    return <Navigate to={redirectTo} replace />;
  }

  return (
    <main className="auth-shell">
      <DocumentTitle title="Completing sign-in" />
      <section className="auth-panel" aria-live="polite">
        <div className="auth-brand">
          <div className="brand-mark auth-brand__mark" aria-hidden="true">
            <img src="/bedrock-logo.svg" alt="" />
          </div>
          <div>
            <span className="eyebrow">Bedrock</span>
            <h1>Schema Migrator</h1>
          </div>
        </div>
        {failed ? (
          <div className="status-banner status-banner--error" role="alert">
            <span>Sign-in callback failed.</span>
            <Link className="button button--secondary" to="/login" replace>
              Try again
            </Link>
          </div>
        ) : (
          <div className="empty-state" role="status">Completing sign-in...</div>
        )}
      </section>
    </main>
  );
};
