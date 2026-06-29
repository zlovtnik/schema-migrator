import { useEffect, useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { EyeIcon } from "@phosphor-icons/react/dist/csr/Eye";
import { EyeSlashIcon } from "@phosphor-icons/react/dist/csr/EyeSlash";
import { PlugsConnectedIcon } from "@phosphor-icons/react/dist/csr/PlugsConnected";
import { useForm } from "react-hook-form";
import {
  envOptions,
  targetFormSchema,
  type ConnectionTestResult,
  type Target,
  type TargetFormValues
} from "../types";
import { Icon } from "./ui/Icon";

interface ConnectionFormProps {
  initialTarget?: Target | undefined;
  submitting?: boolean | undefined;
  testResult?: ConnectionTestResult | undefined;
  testing?: boolean | undefined;
  onSubmit: (values: TargetFormValues) => void;
  onCancel?: () => void;
  onTest?: (values: TargetFormValues) => Promise<ConnectionTestResult> | void;
}

const defaultsFromTarget = (target?: Target): TargetFormValues => ({
  label: target?.label ?? "",
  app_name: target?.app_name ?? "",
  env: target?.env ?? "dev",
  jdbc_url: target?.jdbc_url ?? "",
  password: ""
});

const fieldIds = {
  label: "target-label",
  app_name: "target-app-name",
  env: "target-env",
  jdbc_url: "target-jdbc-url",
  password: "target-password"
} satisfies Record<keyof TargetFormValues, string>;

export const ConnectionForm = ({
  initialTarget,
  submitting = false,
  testResult,
  testing = false,
  onSubmit,
  onCancel,
  onTest
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
      <div className="form-grid">
        <label htmlFor={fieldIds.label}>
          Label{renderRequired()}
          <input id={fieldIds.label} {...register("label")} autoComplete="off" {...fieldState("label")} />
          {renderError("label")}
        </label>
        <label htmlFor={fieldIds.app_name}>
          App{renderRequired()}
          <input id={fieldIds.app_name} {...register("app_name")} autoComplete="off" {...fieldState("app_name")} />
          {renderError("app_name")}
        </label>
        <label htmlFor={fieldIds.env}>
          Environment{renderRequired()}
          <select id={fieldIds.env} {...register("env")} {...fieldState("env")}>
            {envOptions.map((env) => (
              <option value={env} key={env}>
                {env}
              </option>
            ))}
          </select>
          {renderError("env")}
        </label>
        <label className="form-field--wide" htmlFor={fieldIds.jdbc_url}>
          JDBC URL{renderRequired()}
          <input
            id={fieldIds.jdbc_url}
            {...register("jdbc_url")}
            autoComplete="off"
            placeholder="jdbc:postgresql://localhost:5432/app?user=app"
            spellCheck={false}
            {...fieldState("jdbc_url")}
          />
          {renderError("jdbc_url")}
        </label>
      </div>

      <label htmlFor={fieldIds.password}>
        Password
        <span className="password-input">
          <input
            id={fieldIds.password}
            {...register("password")}
            type={showPassword ? "text" : "password"}
            autoComplete="current-password"
            {...fieldState("password", false)}
          />
          <button className="icon-button" type="button" onClick={() => setShowPassword((value) => !value)}>
            <Icon source={showPassword ? EyeSlashIcon : EyeIcon} size={16} />
            <span className="sr-only">{showPassword ? "Hide password" : "Show password"}</span>
          </button>
        </span>
        {renderError("password")}
      </label>

      {testResult ? (
        <div className={testResult.ok ? "inline-result inline-result--ok" : "inline-result inline-result--error"} role="status">
          {testResult.ok ? `Connected in ${testResult.latency_ms ?? 0} ms` : testResult.error ?? "Connection failed"}
        </div>
      ) : null}

      <div className="form-actions">
        <button className="button button--secondary" type="button" onClick={handleSubmit(test)} disabled={!onTest || testing}>
          <Icon source={PlugsConnectedIcon} size={16} />
          {testing ? "Testing" : "Test connection"}
        </button>
        {onCancel ? (
          <button className="button button--ghost" type="button" onClick={onCancel}>
            Cancel
          </button>
        ) : null}
        <button className="button button--primary" type="submit" disabled={submitting}>
          {submitting ? "Saving" : initialTarget ? "Save target" : "Create target"}
        </button>
      </div>
    </form>
  );
};
