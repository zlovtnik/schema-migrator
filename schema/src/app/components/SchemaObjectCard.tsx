import React from 'react';
import { LucideIcon } from 'lucide-react';

interface SchemaObjectCardProps {
  icon: LucideIcon;
  label: string;
  count: number;
  active?: boolean;
  onClick?: () => void;
}

export function SchemaObjectCard({ icon: Icon, label, count, active, onClick }: SchemaObjectCardProps) {
  return (
    <button
      onClick={onClick}
      className={`
        flex items-center justify-between w-full p-4 rounded-xl transition-all duration-200 border
        ${
          active
            ? 'bg-amber-500/10 border-amber-500/30 text-amber-500'
            : 'bg-neutral-900 border-neutral-800 text-neutral-400 hover:text-white hover:border-neutral-700 hover:bg-neutral-800'
        }
      `}
    >
      <div className="flex items-center gap-3">
        <Icon className="w-5 h-5 flex-shrink-0" />
        <span className="font-medium">{label}</span>
      </div>
      <span className="font-mono font-bold text-sm">{count}</span>
    </button>
  );
}
