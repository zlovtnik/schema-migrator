import { useEffect, useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { EyeIcon } from "@phosphor-icons/react/dist/csr/Eye";
import { EyeSlashIcon } from "@phosphor-icons/react/dist/csr/EyeSlash";
import { PlugsConnectedIcon } from "@phosphor-icons/react/dist/csr/PlugsConnected";
import { useForm } from "react-hook-form";
import { envOptions, targetFormSchema, type ConnectionTestResult, type Target, type TargetFormValues } from "../types";
import { Icon } from "./ui/Icon";

interface ConnectionFormProps {
  initialTarget?: Target | undefined;
  submitting?: boolean | undefined;
  testResult?: ConnectionTestResult | undefined;
  testing?: boolean | undefined;
  readOnly?: boolean | undefined;
  readOnlyReason?: string | undefined;
  onSubmit: (values: TargetFormValues) => void;
  onCancel?: () => void;
  onTest?: (values: TargetFormValues) => Promise<ConnectionTestResult> | void;
  onCredentialsChange?: () => void;
}

const defaultsFromTarget = (target?: Target): TargetFormValues => ({
  label: target?.label ?? "",
  app_name: target?.app_name ?? "",
  env: target?.env ?? "dev",
  jdbc_url: target?.jdbc_url ?? "",
  password: "",
  repo_url: target?.repo_url ?? "",
  repo_branch: target?.repo_branch ?? "main",
  repo_sql_path: target?.repo_sql_path ?? "sql"
});

const fieldIds = {
  label: "target-label",
  app_name: "target-app-name",
  env: "target-env",
  jdbc_url: "target-jdbc-url",
  password: "target-password",
  repo_url: "target-repo-url",
  repo_branch: "target-repo-branch",
  repo_sql_path: "target-repo-sql-path"
} satisfies Record<keyof TargetFormValues, string>;

export const ConnectionForm = ({
  initialTarget,
  submitting = false,
  testResult,
  testing = false,
  readOnly = false,
  readOnlyReason,
  onSubmit,
  onCancel,
  onTest,
  onCredentialsChange
}: ConnectionFormProps) => {
  const [showPassword, setShowPassword] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset
  } = useForm<TargetFormValues>({
    resolver: zodResolver(targetFormSchema),
    defaultValues: defaultsFromTarget(initialTarget)
  });

  useEffect(() => {
    reset(defaultsFromTarget(initialTarget));
  }, [initialTarget, reset]);

  const submit = (values: TargetFormValues) => {
    onSubmit(values);
  };

  const test = async (values: TargetFormValues) => {
    await onTest?.(values);
  };

  const jdbcUrlField = onCredentialsChange
    ? register("jdbc_url", { onChange: onCredentialsChange })
    : register("jdbc_url");
  const passwordField = onCredentialsChange
    ? register("password", { onChange: onCredentialsChange })
    : register("password");

  const errorId = (name: keyof TargetFormValues) => `${fieldIds[name]}-error`;
  const fieldState = (name: keyof TargetFormValues, required = true) => ({
    "aria-describedby": errors[name] ? errorId(name) : undefined,
    "aria-invalid": Boolean(errors[name]) || undefined,
    "aria-required": required || undefined
  });
  const renderRequired = () => <span aria-hidden="true"> *</span>;
  const renderError = (name: keyof TargetFormValues) =>
    errors[name] ? (
      <span className="field-error" id={errorId(name)} role="alert">
        {errors[name]?.message}
      </span>
    ) : null;

  return (
    <form className="connection-form" onSubmit={handleSubmit(submit)}>
      <p className="form-hint">Fields marked with * are required.</p>
      {readOnly ? (
        <div className="status-banner" role="status">
          {readOnlyReason ?? "This target is read-only for the current session."}
        </div>
      ) : null}

      <div className="form-grid">
        <label htmlFor={fieldIds.label}>
          Label{renderRequired()}
          <input
            id={fieldIds.label}
            {...register("label")}
            autoComplete="off"
            disabled={readOnly}
            {...fieldState("label")}
          />
          {renderError("label")}
        </label>
        <label htmlFor={fieldIds.app_name}>
          App{renderRequired()}
          <input
            id={fieldIds.app_name}
            {...register("app_name")}
            autoComplete="off"
            disabled={readOnly}
            {...fieldState("app_name")}
          />
          {renderError("app_name")}
        </label>
        <label htmlFor={fieldIds.env}>
          Environment{renderRequired()}
          <select id={fieldIds.env} {...register("env")} disabled={readOnly} {...fieldState("env")}>
            {envOptions.map((env) => (
              <option value={env} key={env}>
                {env}
              </option>
            ))}
          </select>
          {renderError("env")}
        </label>
        <label className="form-field--wide" htmlFor={fieldIds.jdbc_url}>
          Database URL{renderRequired()}
          <input
            id={fieldIds.jdbc_url}
            {...jdbcUrlField}
            autoComplete="off"
            disabled={readOnly}
            placeholder="postgres://user:password@localhost:5432/app"
            spellCheck={false}
            {...fieldState("jdbc_url")}
          />
          {renderError("jdbc_url")}
        </label>
      </div>

      <div className="form-grid">
        <label className="form-field--wide" htmlFor={fieldIds.repo_url}>
          Repository URL{renderRequired()}
          <input
            id={fieldIds.repo_url}
            {...register("repo_url")}
            autoComplete="off"
            disabled={readOnly}
            placeholder="https://github.com/example/schema-repo.git"
            spellCheck={false}
            {...fieldState("repo_url")}
          />
          {renderError("repo_url")}
        </label>
        <label htmlFor={fieldIds.repo_branch}>
          Branch{renderRequired()}
          <input
            id={fieldIds.repo_branch}
            {...register("repo_branch")}
            autoComplete="off"
            disabled={readOnly}
            {...fieldState("repo_branch")}
          />
          {renderError("repo_branch")}
        </label>
        <label htmlFor={fieldIds.repo_sql_path}>
          SQL path{renderRequired()}
          <input
            id={fieldIds.repo_sql_path}
            {...register("repo_sql_path")}
            autoComplete="off"
            disabled={readOnly}
            {...fieldState("repo_sql_path")}
          />
          {renderError("repo_sql_path")}
        </label>
      </div>

      <label htmlFor={fieldIds.password}>
        Password
        <span className="password-input">
          <input
            id={fieldIds.password}
            {...passwordField}
            type={showPassword ? "text" : "password"}
            autoComplete="current-password"
            disabled={readOnly}
            {...fieldState("password", false)}
          />
          <button
            className="icon-button"
            type="button"
            onClick={() => setShowPassword((value) => !value)}
            disabled={readOnly}
          >
            <Icon source={showPassword ? EyeSlashIcon : EyeIcon} size={16} />
            <span className="sr-only">{showPassword ? "Hide password" : "Show password"}</span>
          </button>
        </span>
        {renderError("password")}
      </label>

      {testResult ? (
        <div
          className={testResult.ok ? "inline-result inline-result--ok" : "inline-result inline-result--error"}
          role="status"
        >
          {testResult.ok ? `Connected in ${testResult.latency_ms ?? 0} ms` : (testResult.error ?? "Connection failed")}
        </div>
      ) : null}

      <div className="form-actions">
        <button
          className="button button--secondary"
          type="button"
          onClick={handleSubmit(test)}
          disabled={!onTest || testing || readOnly}
          title={readOnly ? readOnlyReason : undefined}
        >
          <Icon source={PlugsConnectedIcon} size={16} />
          {testing ? "Testing" : "Test connection"}
        </button>
        {onCancel ? (
          <button className="button button--ghost" type="button" onClick={onCancel}>
            Cancel
          </button>
        ) : null}
        <button
          className="button button--primary"
          type="submit"
          disabled={submitting || readOnly}
          title={readOnly ? readOnlyReason : undefined}
        >
          {submitting ? "Saving" : initialTarget ? "Save target" : "Create target"}
        </button>
      </div>
    </form>
  );
};
