import React from 'react';
import { Sidebar } from './components/Sidebar';
import { Header } from './components/Header';
import { Breadcrumb } from './components/Breadcrumb';
import { SchemaObjectCard } from './components/SchemaObjectCard';
import { SchemaTable } from './components/SchemaTable';
import { Database, Box, GitBranch, Layers, Eye } from 'lucide-react';

export default function App() {
  const schemaObjectTypes = [
    { id: 'all', label: 'All objects', icon: Database, count: 231, active: true },
    { id: 'extension', label: 'Extension', icon: Box, count: 4 },
    { id: 'function', label: 'Function', icon: GitBranch, count: 68 },
    { id: 'index', label: 'Index', icon: Layers, count: 109 },
    { id: 'view', label: 'Materialized View', icon: Eye, count: 3 },
  ];

  return (
    <div className="flex h-screen bg-neutral-950 text-white">
      {/* Sidebar */}
      <Sidebar />

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Header */}
        <Header />

        {/* Main Content */}
        <main className="flex-1 overflow-y-auto">
          <div className="max-w-7xl mx-auto px-8 py-6">
            {/* Breadcrumb */}
            <Breadcrumb items={[{ label: 'Bedrock', href: '/overview' }, { label: 'Schema' }]} />

            {/* Page Header */}
            <div className="mb-8">
              <div className="flex items-start justify-between mb-6">
                <div className="flex-1">
                  <div className="inline-flex items-center gap-2 text-xs font-semibold text-amber-500 uppercase tracking-wider mb-2">
                    <Database className="w-4 h-4" />
                    Schema browser
                  </div>
                  <h1 className="text-3xl font-bold mb-2">Tracked objects</h1>
                  <p className="text-neutral-400 text-base">
                    Inspect manifest-defined and live Postgres schema objects for the selected target.
                  </p>
                </div>

                {/* Target Selector */}
                <div className="ml-6">
                  <label className="flex flex-col gap-2">
                    <span className="text-xs font-semibold text-neutral-500 uppercase tracking-wider">
                      Target
                    </span>
                    <select className="bg-neutral-800 border border-neutral-700 text-white rounded-lg px-4 py-2 min-w-[200px] focus:outline-none focus:ring-2 focus:ring-amber-500">
                      <option>app (dev)</option>
                    </select>
                  </label>
                </div>
              </div>
            </div>

            {/* Content Grid */}
            <div className="grid grid-cols-12 gap-6">
              {/* Schema Object Type Sidebar */}
              <div className="col-span-3">
                <div className="sticky top-6 space-y-2">
                  {schemaObjectTypes.map((type) => (
                    <SchemaObjectCard
                      key={type.id}
                      icon={type.icon}
                      label={type.label}
                      count={type.count}
                      active={type.active}
                    />
                  ))}
                </div>
              </div>

              {/* Main Content - Schema Table */}
              <div className="col-span-9">
                <SchemaTable />
              </div>
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}
