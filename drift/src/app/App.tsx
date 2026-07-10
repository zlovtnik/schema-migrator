import React from 'react';
import { Sidebar } from './components/Sidebar';
import { Header } from './components/Header';
import { StatCard } from './components/StatCard';
import { DriftTable } from './components/DriftTable';
import { ChevronRight, CheckCircle, Target } from 'lucide-react';

export default function App() {
  return (
    <div className="bg-[rgb(16,15,13)] text-[rgb(237,233,224)] min-h-screen flex">
      {/* Sidebar */}
      <Sidebar />

      {/* Main Content */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Header */}
        <Header />

        {/* Main Area */}
        <main className="flex-1 overflow-auto">
          {/* Breadcrumb */}
          <nav className="max-w-7xl mx-auto px-8 pt-6">
            <ol className="flex items-center gap-2 text-xs font-bold">
              <li>
                <a href="/overview" className="text-[rgb(120,114,107)] hover:text-[rgb(237,233,224)] transition-colors">
                  Bedrock
                </a>
              </li>
              <ChevronRight size={14} className="text-[rgb(120,114,107)]" />
              <li className="text-[rgb(237,233,224)]">Drift</li>
            </ol>
          </nav>

          {/* Page Content */}
          <div className="max-w-7xl mx-auto px-8 py-6 space-y-8">
            {/* Page Header */}
            <div className="flex flex-wrap items-start justify-between gap-6">
              <div className="min-w-[280px]">
                <div className="text-xs font-bold uppercase tracking-wide text-[rgb(120,114,107)] mb-2">
                  Observe
                </div>
                <h1 className="text-3xl font-bold mb-2">Schema Drift</h1>
                <p className="text-[rgb(120,114,107)]">
                  Compare manifest-defined objects with the selected target catalog.
                </p>
              </div>

              <div className="min-w-[280px]">
                <label className="block">
                  <div className="text-xs font-extrabold uppercase text-[rgb(120,114,107)] mb-2 flex items-center gap-2">
                    <Target size={14} />
                    Target Environment
                  </div>
                  <select className="w-full bg-[rgb(32,30,25)] border border-[rgba(255,230,160,0.09)] rounded px-4 py-2.5 text-sm">
                    <option>app (dev)</option>
                    <option>app (staging)</option>
                    <option>app (production)</option>
                  </select>
                </label>
              </div>
            </div>

            {/* Control State Section */}
            <section className="bg-[rgb(23,21,18)] border border-[rgba(255,230,160,0.09)] rounded-lg p-6 space-y-6">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <div className="text-xs font-bold uppercase tracking-wide text-[rgb(120,114,107)] mb-2">
                    Schema Control
                  </div>
                  <h2 className="text-xl font-bold">Control State</h2>
                </div>
                <span className="inline-flex items-center gap-2 bg-[rgba(107,203,138,0.16)] border border-[rgba(107,203,138,0.4)] text-[rgb(107,203,138)] px-3 py-1.5 rounded text-xs font-bold uppercase">
                  <CheckCircle size={16} />
                  Clean
                </span>
              </div>

              {/* Stats Grid */}
              <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">
                <StatCard label="Total" value="125" />
                <StatCard label="Applied" value="9" variant="success" />
                <StatCard label="Skipped" value="116" />
                <StatCard label="Pending" value="0" variant="warning" />
                <StatCard label="Failed" value="0" variant="error" />
              </div>

              {/* Details */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 pt-4 border-t border-[rgba(255,230,160,0.09)]">
                <div>
                  <dt className="text-xs font-extrabold text-[rgb(120,114,107)] mb-1">
                    Last Applied
                  </dt>
                  <dd className="text-sm">7/8/2026, 5:51:29 PM</dd>
                </div>
                <div>
                  <dt className="text-xs font-extrabold text-[rgb(120,114,107)] mb-1">
                    Manifest Version
                  </dt>
                  <dd className="text-sm font-mono">v2.4.1-beta</dd>
                </div>
                <div>
                  <dt className="text-xs font-extrabold text-[rgb(120,114,107)] mb-1">
                    Drift Detected
                  </dt>
                  <dd className="text-sm">2 objects</dd>
                </div>
              </div>
            </section>

            {/* Drift Detection Table */}
            <section className="space-y-4">
              <div>
                <h2 className="text-xl font-bold mb-2">Object Drift Status</h2>
                <p className="text-sm text-[rgb(120,114,107)]">
                  Detailed view of all database objects and their drift status
                </p>
              </div>
              <DriftTable />
            </section>
          </div>
        </main>
      </div>
    </div>
  );
}
