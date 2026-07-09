import React from 'react';
import { Database, Target, FileText, GitBranch, Activity, TrendingUp, CheckCircle, Camera, Settings, Shield, ChevronLeft } from 'lucide-react';

interface NavItem {
  label: string;
  icon: React.ReactNode;
  href: string;
  active?: boolean;
  count?: number;
}

interface NavSection {
  title: string;
  items: NavItem[];
}

export function Sidebar() {
  const navSections: NavSection[] = [
    {
      title: 'Operate',
      items: [
        { label: 'Overview', icon: <Activity className="w-5 h-5" />, href: '/overview' },
        { label: 'Targets', icon: <Target className="w-5 h-5" />, href: '/targets' },
        { label: 'Schema', icon: <Database className="w-5 h-5" />, href: '/schema', active: true },
        { label: 'SQL Files', icon: <FileText className="w-5 h-5" />, href: '/sql-files' },
        { label: 'Migrations', icon: <GitBranch className="w-5 h-5" />, href: '/migrations' },
      ],
    },
    {
      title: 'Observe',
      items: [
        { label: 'Runs', icon: <Activity className="w-5 h-5" />, href: '/runs' },
        { label: 'Drift', icon: <TrendingUp className="w-5 h-5" />, href: '/drift' },
        { label: 'Validation', icon: <CheckCircle className="w-5 h-5" />, href: '/validation/latest' },
        { label: 'Snapshots', icon: <Camera className="w-5 h-5" />, href: '/snapshots' },
      ],
    },
    {
      title: 'Settings',
      items: [
        { label: 'Application', icon: <Settings className="w-5 h-5" />, href: '/settings' },
      ],
    },
    {
      title: 'Admin',
      items: [
        { label: 'Audit', icon: <Shield className="w-5 h-5" />, href: '/audit' },
      ],
    },
  ];

  return (
    <aside className="w-64 bg-neutral-900 border-r border-neutral-800 flex flex-col h-screen">
      {/* Logo Section */}
      <div className="p-4 border-b border-neutral-800">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-gradient-to-br from-amber-500 to-orange-600 rounded-lg flex items-center justify-center shadow-lg">
            <Database className="w-6 h-6 text-white" />
          </div>
          <div>
            <div className="font-semibold text-white">Bedrock Schema</div>
            <div className="text-xs text-neutral-400">Migration operations</div>
          </div>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto p-3 space-y-6">
        {navSections.map((section) => (
          <div key={section.title}>
            <div className="text-xs font-bold text-neutral-500 uppercase tracking-wider px-3 mb-2">
              {section.title}
            </div>
            <div className="space-y-1">
              {section.items.map((item) => (
                <a
                  key={item.label}
                  href={item.href}
                  className={`
                    flex items-center gap-3 px-3 py-2 rounded-lg transition-all duration-200
                    ${
                      item.active
                        ? 'bg-amber-500/10 text-amber-500 font-medium'
                        : 'text-neutral-400 hover:text-white hover:bg-neutral-800'
                    }
                  `}
                >
                  {item.icon}
                  <span>{item.label}</span>
                </a>
              ))}
            </div>
          </div>
        ))}
      </nav>

      {/* Target Selector */}
      <div className="p-4 border-t border-neutral-800 space-y-3">
        <div className="flex items-center gap-2 text-xs font-semibold text-neutral-500 uppercase tracking-wider">
          <Target className="w-4 h-4" />
          <span>Target</span>
        </div>
        <select className="w-full bg-neutral-800 border border-neutral-700 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-amber-500">
          <option>app (dev)</option>
        </select>
        
        {/* Collapse Button */}
        <button className="w-full flex items-center justify-center gap-2 px-3 py-2 text-neutral-400 hover:text-white hover:bg-neutral-800 rounded-lg transition-colors">
          <ChevronLeft className="w-5 h-5" />
          <span className="text-sm">Collapse</span>
        </button>
      </div>
    </aside>
  );
}
