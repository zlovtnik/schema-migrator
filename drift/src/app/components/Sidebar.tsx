import React, { useState } from 'react';
import { ChevronLeft, LayoutDashboard, Target, Database, FileCode, GitBranch, Activity, AlertTriangle, CheckCircle, Camera, Settings, FileText } from 'lucide-react';

interface NavItem {
  label: string;
  icon: React.ReactNode;
  href: string;
  active?: boolean;
}

interface NavSection {
  title: string;
  items: NavItem[];
}

export function Sidebar() {
  const [collapsed, setCollapsed] = useState(false);

  const navSections: NavSection[] = [
    {
      title: 'Operate',
      items: [
        { label: 'Overview', icon: <LayoutDashboard size={20} />, href: '/overview' },
        { label: 'Targets', icon: <Target size={20} />, href: '/targets' },
        { label: 'Schema', icon: <Database size={20} />, href: '/schema' },
        { label: 'SQL Files', icon: <FileCode size={20} />, href: '/sql-files' },
        { label: 'Migrations', icon: <GitBranch size={20} />, href: '/migrations' },
      ],
    },
    {
      title: 'Observe',
      items: [
        { label: 'Runs', icon: <Activity size={20} />, href: '/runs' },
        { label: 'Drift', icon: <AlertTriangle size={20} />, href: '/drift', active: true },
        { label: 'Validation', icon: <CheckCircle size={20} />, href: '/validation/latest' },
        { label: 'Snapshots', icon: <Camera size={20} />, href: '/snapshots' },
      ],
    },
    {
      title: 'Settings',
      items: [
        { label: 'Application', icon: <Settings size={20} />, href: '/settings' },
      ],
    },
    {
      title: 'Admin',
      items: [
        { label: 'Audit', icon: <FileText size={20} />, href: '/audit' },
      ],
    },
  ];

  if (collapsed) {
    return (
      <aside className="bg-[rgb(20,18,16)] border-r border-[rgba(255,230,160,0.09)] w-20 h-screen flex flex-col items-center py-4 gap-6">
        <div className="w-12 h-12 bg-[rgba(224,150,60,0.1)] border border-[rgba(224,150,60,0.3)] rounded-lg flex items-center justify-center">
          <Database size={24} className="text-[rgb(224,150,60)]" />
        </div>
        <nav className="flex flex-col gap-2 w-full px-2">
          {navSections.flatMap((section) =>
            section.items.map((item) => (
              <a
                key={item.label}
                href={item.href}
                className={`p-3 rounded-lg transition-colors ${
                  item.active
                    ? 'bg-[rgba(224,150,60,0.12)] text-[rgb(224,150,60)]'
                    : 'text-[rgb(120,114,107)] hover:bg-[rgba(255,230,160,0.05)]'
                }`}
                title={item.label}
              >
                {item.icon}
              </a>
            ))
          )}
        </nav>
        <button
          onClick={() => setCollapsed(false)}
          className="mt-auto p-3 text-[rgb(120,114,107)] hover:bg-[rgba(255,230,160,0.05)] rounded-lg transition-colors"
        >
          <ChevronLeft size={20} className="rotate-180" />
        </button>
      </aside>
    );
  }

  return (
    <aside className="bg-[rgb(20,18,16)] border-r border-[rgba(255,230,160,0.09)] w-56 h-screen flex flex-col">
      {/* Header */}
      <div className="p-4 border-b border-[rgba(255,230,160,0.09)] flex items-center gap-3">
        <div className="w-10 h-10 bg-[rgba(224,150,60,0.1)] border border-[rgba(224,150,60,0.3)] rounded-lg flex items-center justify-center shrink-0">
          <Database size={20} className="text-[rgb(224,150,60)]" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="font-bold text-sm truncate">Bedrock Schema Migrator</div>
          <div className="text-xs text-[rgb(120,114,107)] truncate">Migration operations</div>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto p-3 space-y-6">
        {navSections.map((section) => (
          <div key={section.title}>
            <div className="px-2 mb-2 text-[10px] font-extrabold uppercase tracking-wider text-[rgb(120,114,107)]">
              {section.title}
            </div>
            <div className="space-y-1">
              {section.items.map((item) => (
                <a
                  key={item.label}
                  href={item.href}
                  className={`flex items-center gap-3 px-2 py-2 rounded-lg transition-colors ${
                    item.active
                      ? 'bg-[rgba(224,150,60,0.12)] text-[rgb(224,150,60)] font-semibold'
                      : 'text-[rgb(120,114,107)] hover:bg-[rgba(255,230,160,0.05)] font-medium'
                  }`}
                >
                  {item.icon}
                  <span className="text-sm">{item.label}</span>
                </a>
              ))}
            </div>
          </div>
        ))}
      </nav>

      {/* Target Selector */}
      <div className="p-3 border-t border-[rgba(255,230,160,0.09)]">
        <label className="block">
          <div className="text-[10px] font-extrabold uppercase tracking-wider text-[rgb(120,114,107)] mb-2 flex items-center gap-2">
            <Target size={14} />
            Target
          </div>
          <select className="w-full bg-[rgb(32,30,25)] border border-[rgba(255,230,160,0.09)] rounded px-3 py-2 text-xs font-mono">
            <option>app (dev)</option>
          </select>
        </label>
      </div>

      {/* Collapse Button */}
      <button
        onClick={() => setCollapsed(true)}
        className="m-3 mt-0 flex items-center gap-2 px-2 py-2 text-sm text-[rgb(120,114,107)] hover:bg-[rgba(255,230,160,0.05)] rounded-lg transition-colors"
      >
        <ChevronLeft size={20} />
        <span>Collapse</span>
      </button>
    </aside>
  );
}
