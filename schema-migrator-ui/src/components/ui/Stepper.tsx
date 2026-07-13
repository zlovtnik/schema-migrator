import { CheckIcon } from "@phosphor-icons/react/dist/csr/Check";
import { Icon } from "./Icon";

interface StepperProps {
  steps: readonly string[];
  currentIndex: number;
  label: string;
}

export const Stepper = ({ currentIndex, label, steps }: StepperProps) => (
  <nav className="upgrade-progress glass-surface" aria-label={label}>
    <div className="upgrade-progress__summary">
      <span>{label}</span>
      <strong>
        Phase {currentIndex + 1} of {steps.length} · {steps[currentIndex]}
      </strong>
    </div>
    <ol className="upgrade-stepper">
      {steps.map((step, index) => {
        const state = index === currentIndex ? "Current" : index < currentIndex ? "Complete" : "Upcoming";
        return (
          <li
            key={step}
            className={
              index === currentIndex
                ? "upgrade-step upgrade-step--active"
                : index < currentIndex
                  ? "upgrade-step upgrade-step--done"
                  : "upgrade-step"
            }
            aria-current={index === currentIndex ? "step" : undefined}
          >
            <span className="upgrade-step__marker" aria-hidden="true">
              {index < currentIndex ? <Icon source={CheckIcon} size={16} weight="bold" /> : index + 1}
            </span>
            <span className="upgrade-step__copy">
              <strong>{step}</strong>
              <small>{state}</small>
            </span>
          </li>
        );
      })}
    </ol>
  </nav>
);
