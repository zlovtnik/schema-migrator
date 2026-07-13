interface ProgressBarProps {
  value: number;
  max: number;
  label: string;
  liveText?: string;
}

export const ProgressBar = ({ label, liveText, max, value }: ProgressBarProps) => {
  const safeMax = Math.max(1, max);
  const safeValue = Math.min(Math.max(0, value), safeMax);
  const percentage = Math.round((safeValue / safeMax) * 100);

  return (
    <div className="ui-progress">
      <div
        aria-label={label}
        aria-valuemax={safeMax}
        aria-valuemin={0}
        aria-valuenow={safeValue}
        className="ui-progress__track"
        role="progressbar"
      >
        <div className="ui-progress__bar" style={{ width: `${percentage}%` }} />
      </div>
      <span className="ui-progress__label">{liveText ?? `${safeValue} of ${safeMax}`}</span>
      <span className="sr-only" aria-live="polite">
        {liveText ?? `${safeValue} of ${safeMax} complete`}
      </span>
    </div>
  );
};
