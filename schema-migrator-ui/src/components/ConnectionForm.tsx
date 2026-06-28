import { useEffect, useMemo, useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { EyeIcon } from "@phosphor-icons/react/dist/csr/Eye";
import { EyeSlashIcon } from "@phosphor-icons/react/dist/csr/EyeSlash";
import { PlugsConnectedIcon } from "@phosphor-icons/react/dist/csr/PlugsConnected";
import { useForm } from "react-hook-form";
import { z } from "zod";
import {
  envOptions,
  sslModeOptions,
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
  host: target?.host ?? "",
  port: target?.port ?? 5432,
  dbname: target?.dbname ?? "",
  user: target?.user ?? "",
  password: "",
  schema: target?.schema ?? "public",
  ssl_mode: target?.ssl_mode ?? "require"
});

const fieldIds = {
  label: "target-label",
  app_name: "target-app-name",
  env: "target-env",
  host: "target-host",
  port: "target-port",
  dbname: "target-dbname",
  user: "target-user",
  password: "target-password",
  schema: "target-schema",
  ssl_mode: "target-ssl-mode"
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
  const [changePassword, setChangePassword] = useState(!initialTarget);

  const schema = useMemo(
    () =>
      targetFormSchema.superRefine((values, ctx) => {
        if ((!initialTarget || changePassword) && !values.password?.trim()) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: initialTarget ? "Password is required to change it" : "Password is required for new targets",
            path: ["password"]
          });
        }
      }),
    [changePassword, initialTarget]
  );

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset
  } = useForm<TargetFormValues>({
    resolver: zodResolver(schema),
    defaultValues: defaultsFromTarget(initialTarget)
  });

  useEffect(() => {
    reset(defaultsFromTarget(initialTarget));
    setChangePassword(!initialTarget);
  }, [initialTarget, reset]);

  const submit = (values: TargetFormValues) => {
    onSubmit(changePassword ? values : { ...values, password: "" });
  };

  const test = async (values: TargetFormValues) => {
    await onTest?.(changePassword ? values : { ...values, password: "" });
  };

  const errorId = (name: keyof TargetFormValues) => `${fieldIds[name]}-error`;
  const fieldState = (name: keyof TargetFormValues) => ({
    "aria-describedby": errors[name] ? errorId(name) : undefined,
    "aria-invalid": Boolean(errors[name]) || undefined,
    "aria-required": true
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
        <label htmlFor={fieldIds.host}>
          Host{renderRequired()}
          <input id={fieldIds.host} {...register("host")} autoComplete="off" {...fieldState("host")} />
          {renderError("host")}
        </label>
        <label htmlFor={fieldIds.port}>
          Port{renderRequired()}
          <input id={fieldIds.port} {...register("port")} inputMode="numeric" {...fieldState("port")} />
          {renderError("port")}
        </label>
        <label htmlFor={fieldIds.dbname}>
          Database{renderRequired()}
          <input id={fieldIds.dbname} {...register("dbname")} autoComplete="off" {...fieldState("dbname")} />
          {renderError("dbname")}
        </label>
        <label htmlFor={fieldIds.user}>
          User{renderRequired()}
          <input id={fieldIds.user} {...register("user")} autoComplete="off" {...fieldState("user")} />
          {renderError("user")}
        </label>
        <label htmlFor={fieldIds.schema}>
          Schema{renderRequired()}
          <input id={fieldIds.schema} {...register("schema")} autoComplete="off" {...fieldState("schema")} />
          {renderError("schema")}
        </label>
        <label htmlFor={fieldIds.ssl_mode}>
          SSL mode{renderRequired()}
          <select id={fieldIds.ssl_mode} {...register("ssl_mode")} {...fieldState("ssl_mode")}>
            {sslModeOptions.map((mode) => (
              <option value={mode} key={mode}>
                {mode}
              </option>
            ))}
          </select>
          {renderError("ssl_mode")}
        </label>
      </div>

      {initialTarget ? (
        <label className="checkbox-row">
          <input type="checkbox" checked={changePassword} onChange={(event) => setChangePassword(event.target.checked)} />
          Change password
        </label>
      ) : null}

      {changePassword ? (
        <label htmlFor={fieldIds.password}>
          Password{renderRequired()}
          <span className="password-input">
            <input
              id={fieldIds.password}
              {...register("password")}
              type={showPassword ? "text" : "password"}
              autoComplete="current-password"
              {...fieldState("password")}
            />
            <button className="icon-button" type="button" onClick={() => setShowPassword((value) => !value)}>
              <Icon source={showPassword ? EyeSlashIcon : EyeIcon} size={16} />
              <span className="sr-only">{showPassword ? "Hide password" : "Show password"}</span>
            </button>
          </span>
          {renderError("password")}
        </label>
      ) : (
        <div className="masked-secret">Password is saved and masked.</div>
      )}

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
