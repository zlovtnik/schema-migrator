import React from 'react';
import { LogOut } from 'lucide-react';

export function Header() {
  return (
    <header className="bg-[rgb(16,15,13)] border-b border-[rgba(255,230,160,0.09)] px-6 h-14 flex items-center justify-between">
      <div className="flex-1" />
      
      <div className="flex items-center gap-3">
        <span className="text-xs text-[rgb(120,114,107)] font-mono font-semibold">
          021232a0-407b-4af5-8f85-0192b2610428
        </span>
        
        <div className="bg-[rgba(107,203,138,0.16)] border border-[rgba(107,203,138,0.4)] rounded px-3 py-1.5 flex items-center gap-2">
          <span className="text-xs font-extrabold uppercase text-[rgb(107,203,138)]">Role</span>
          <span className="text-xs font-black text-[rgb(237,233,224)]">admin</span>
        </div>
        
        <button className="bg-[rgb(32,30,25)] border border-[rgba(255,230,160,0.09)] rounded px-3 py-1.5 flex items-center gap-2 text-xs font-bold hover:bg-[rgb(40,38,33)] transition-colors">
          <LogOut size={16} />
          <span>Sign out</span>
        </button>
      </div>
    </header>
  );
}
