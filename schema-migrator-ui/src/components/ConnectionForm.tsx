import { useEffect, useMemo, useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { Eye, EyeOff, PlugZap } from "lucide-react";
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

interface ConnectionFormProps {
  initialTarget?: Target;
  submitting?: boolean;
  testResult?: ConnectionTestResult;
  testing?: boolean;
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

  const renderError = (name: keyof TargetFormValues) =>
    errors[name] ? <span className="field-error">{errors[name]?.message}</span> : null;

  return (
    <form className="connection-form" onSubmit={handleSubmit(submit)}>
      <div className="form-grid">
        <label>
          Label
          <input {...register("label")} autoComplete="off" />
          {renderError("label")}
        </label>
        <label>
          App
          <input {...register("app_name")} autoComplete="off" />
          {renderError("app_name")}
        </label>
        <label>
          Environment
          <select {...register("env")}>
            {envOptions.map((env) => (
              <option value={env} key={env}>
                {env}
              </option>
            ))}
          </select>
          {renderError("env")}
        </label>
        <label>
          Host
          <input {...register("host")} autoComplete="off" />
          {renderError("host")}
        </label>
        <label>
          Port
          <input {...register("port")} inputMode="numeric" />
          {renderError("port")}
        </label>
        <label>
          Database
          <input {...register("dbname")} autoComplete="off" />
          {renderError("dbname")}
        </label>
        <label>
          User
          <input {...register("user")} autoComplete="off" />
          {renderError("user")}
        </label>
        <label>
          Schema
          <input {...register("schema")} autoComplete="off" />
          {renderError("schema")}
        </label>
        <label>
          SSL mode
          <select {...register("ssl_mode")}>
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
        <label>
          Password
          <span className="password-input">
            <input {...register("password")} type={showPassword ? "text" : "password"} autoComplete="new-password" />
            <button className="icon-button" type="button" onClick={() => setShowPassword((value) => !value)}>
              {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
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
          <PlugZap size={16} aria-hidden="true" />
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
