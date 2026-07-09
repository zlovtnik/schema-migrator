import React from 'react';
import { LogOut } from 'lucide-react';

export function Header() {
  return (
    <header className="h-14 bg-neutral-950 border-b border-neutral-800 flex items-center justify-end px-6 gap-4">
      {/* Session ID */}
      <span className="text-xs font-mono text-neutral-500 px-3 py-1.5 bg-neutral-900 rounded border border-neutral-800">
        021232a0-407b-4af5-8f85-0192b2610428
      </span>

      {/* Role Badge */}
      <div className="flex items-center gap-2 px-3 py-1.5 bg-emerald-500/10 border border-emerald-500/30 rounded text-xs font-semibold">
        <span className="text-emerald-400 uppercase tracking-wide">Role</span>
        <span className="text-white">admin</span>
      </div>

      {/* Sign Out Button */}
      <button className="flex items-center gap-2 px-4 py-2 bg-neutral-800 hover:bg-neutral-700 border border-neutral-700 rounded-lg text-sm font-medium text-white transition-colors">
        <LogOut className="w-4 h-4" />
        <span>Sign out</span>
      </button>
    </header>
  );
}
