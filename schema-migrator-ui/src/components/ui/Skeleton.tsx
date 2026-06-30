interface SkeletonProps {
  rows?: number;
  label?: string;
}

export const Skeleton = ({ rows = 6, label = "Loading" }: SkeletonProps) => (
  <div className="skeleton" role="status" aria-label={label}>
    {Array.from({ length: rows }, (_, index) => (
      <span className="skeleton__row" key={index} />
    ))}
  </div>
);
