import type { ScriptError } from "../types";

interface ErrorDetailProps {
  error: ScriptError;
}

export const ErrorDetail = ({ error }: ErrorDetailProps) => (
  <div className="error-detail" role="region" aria-label="Script error detail">
    <dl>
      <div>
        <dt>DB code</dt>
        <dd>{error.db_code}</dd>
      </div>
      <div>
        <dt>Message</dt>
        <dd>{error.message}</dd>
      </div>
      {error.hint ? (
        <div>
          <dt>Hint</dt>
          <dd>{error.hint}</dd>
        </div>
      ) : null}
      {error.context ? (
        <div>
          <dt>Context</dt>
          <dd>{error.context}</dd>
        </div>
      ) : null}
      {error.line ? (
        <div>
          <dt>Line</dt>
          <dd>{error.line}</dd>
        </div>
      ) : null}
    </dl>
  </div>
);
