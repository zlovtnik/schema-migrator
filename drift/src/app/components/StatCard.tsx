import React from 'react';

interface StatCardProps {
  label: string;
  value: string | number;
  variant?: 'default' | 'success' | 'warning' | 'error';
}

export function StatCard({ label, value, variant = 'default' }: StatCardProps) {
  const variantStyles = {
    default: 'bg-[rgb(32,30,25)] border-[rgba(255,230,160,0.09)]',
    success: 'bg-[rgba(107,203,138,0.08)] border-[rgba(107,203,138,0.2)]',
    warning: 'bg-[rgba(224,150,60,0.08)] border-[rgba(224,150,60,0.2)]',
    error: 'bg-[rgba(220,38,38,0.08)] border-[rgba(220,38,38,0.2)]',
  };

  return (
    <div className={`border rounded-lg p-4 ${variantStyles[variant]}`}>
      <div className="text-xs font-extrabold text-[rgb(120,114,107)] mb-1">
        {label}
      </div>
      <div className="text-2xl font-bold font-mono">
        {value}
      </div>
    </div>
  );
}
