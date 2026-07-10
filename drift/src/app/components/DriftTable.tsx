import React, { useState } from 'react';
import { ChevronDown, ChevronRight, Database, AlertCircle, CheckCircle, Clock } from 'lucide-react';

interface DriftItem {
  id: string;
  objectName: string;
  objectType: string;
  status: 'clean' | 'drift' | 'pending';
  lastChecked: string;
  details?: string;
}

const mockData: DriftItem[] = [
  { id: '1', objectName: 'users_table', objectType: 'Table', status: 'clean', lastChecked: '2 mins ago' },
  { id: '2', objectName: 'products_view', objectType: 'View', status: 'drift', lastChecked: '5 mins ago', details: 'Column type mismatch: expected VARCHAR(255), found TEXT' },
  { id: '3', objectName: 'order_index_idx', objectType: 'Index', status: 'clean', lastChecked: '10 mins ago' },
  { id: '4', objectName: 'calculate_total_fn', objectType: 'Function', status: 'pending', lastChecked: '15 mins ago' },
  { id: '5', objectName: 'audit_trigger', objectType: 'Trigger', status: 'clean', lastChecked: '20 mins ago' },
  { id: '6', objectName: 'sessions_table', objectType: 'Table', status: 'drift', lastChecked: '25 mins ago', details: 'Missing column: session_data' },
];

export function DriftTable() {
  const [expandedRows, setExpandedRows] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState<'all' | 'clean' | 'drift' | 'pending'>('all');

  const toggleRow = (id: string) => {
    const newExpanded = new Set(expandedRows);
    if (newExpanded.has(id)) {
      newExpanded.delete(id);
    } else {
      newExpanded.add(id);
    }
    setExpandedRows(newExpanded);
  };

  const filteredData = filter === 'all' ? mockData : mockData.filter(item => item.status === filter);

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'clean':
        return <CheckCircle size={16} className="text-[rgb(107,203,138)]" />;
      case 'drift':
        return <AlertCircle size={16} className="text-[rgb(224,150,60)]" />;
      case 'pending':
        return <Clock size={16} className="text-[rgb(120,114,107)]" />;
      default:
        return null;
    }
  };

  const getStatusBadge = (status: string) => {
    const styles = {
      clean: 'bg-[rgba(107,203,138,0.16)] text-[rgb(107,203,138)] border-[rgba(107,203,138,0.4)]',
      drift: 'bg-[rgba(224,150,60,0.16)] text-[rgb(224,150,60)] border-[rgba(224,150,60,0.4)]',
      pending: 'bg-[rgba(120,114,107,0.16)] text-[rgb(120,114,107)] border-[rgba(120,114,107,0.4)]',
    };

    return (
      <span className={`inline-flex items-center gap-1.5 px-2 py-1 rounded border text-xs font-bold uppercase ${styles[status as keyof typeof styles]}`}>
        {getStatusIcon(status)}
        {status}
      </span>
    );
  };

  return (
    <div className="bg-[rgb(23,21,18)] border border-[rgba(255,230,160,0.09)] rounded-lg overflow-hidden">
      {/* Filter Tabs */}
      <div className="border-b border-[rgba(255,230,160,0.09)] p-4 flex gap-2">
        {(['all', 'clean', 'drift', 'pending'] as const).map((f) => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`px-3 py-1.5 rounded text-xs font-bold uppercase transition-colors ${
              filter === f
                ? 'bg-[rgba(224,150,60,0.12)] text-[rgb(224,150,60)]'
                : 'text-[rgb(120,114,107)] hover:bg-[rgba(255,230,160,0.05)]'
            }`}
          >
            {f}
          </button>
        ))}
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead className="bg-[rgb(16,15,13)] border-b border-[rgba(255,230,160,0.09)]">
            <tr>
              <th className="text-left px-4 py-3 text-xs font-extrabold uppercase text-[rgb(120,114,107)] w-12"></th>
              <th className="text-left px-4 py-3 text-xs font-extrabold uppercase text-[rgb(120,114,107)]">Object</th>
              <th className="text-left px-4 py-3 text-xs font-extrabold uppercase text-[rgb(120,114,107)]">Type</th>
              <th className="text-left px-4 py-3 text-xs font-extrabold uppercase text-[rgb(120,114,107)]">Status</th>
              <th className="text-left px-4 py-3 text-xs font-extrabold uppercase text-[rgb(120,114,107)]">Last Checked</th>
            </tr>
          </thead>
          <tbody>
            {filteredData.map((item) => (
              <React.Fragment key={item.id}>
                <tr
                  className="border-b border-[rgba(255,230,160,0.05)] hover:bg-[rgba(255,230,160,0.03)] transition-colors cursor-pointer"
                  onClick={() => item.details && toggleRow(item.id)}
                >
                  <td className="px-4 py-3">
                    {item.details && (
                      expandedRows.has(item.id) ? (
                        <ChevronDown size={16} className="text-[rgb(120,114,107)]" />
                      ) : (
                        <ChevronRight size={16} className="text-[rgb(120,114,107)]" />
                      )
                    )}
                  </td>
                  <td className="px-4 py-3 font-mono text-sm">{item.objectName}</td>
                  <td className="px-4 py-3 text-sm">
                    <span className="inline-flex items-center gap-1.5 text-[rgb(120,114,107)]">
                      <Database size={14} />
                      {item.objectType}
                    </span>
                  </td>
                  <td className="px-4 py-3">{getStatusBadge(item.status)}</td>
                  <td className="px-4 py-3 text-sm text-[rgb(120,114,107)]">{item.lastChecked}</td>
                </tr>
                {expandedRows.has(item.id) && item.details && (
                  <tr className="bg-[rgba(224,150,60,0.05)] border-b border-[rgba(255,230,160,0.05)]">
                    <td colSpan={5} className="px-4 py-3">
                      <div className="flex items-start gap-3 ml-8">
                        <AlertCircle size={16} className="text-[rgb(224,150,60)] mt-0.5 shrink-0" />
                        <div>
                          <div className="text-xs font-bold text-[rgb(224,150,60)] mb-1">Drift Details</div>
                          <div className="text-sm text-[rgb(237,233,224)]">{item.details}</div>
                        </div>
                      </div>
                    </td>
                  </tr>
                )}
              </React.Fragment>
            ))}
          </tbody>
        </table>
      </div>

      {filteredData.length === 0 && (
        <div className="text-center py-12 text-[rgb(120,114,107)]">
          <Database size={48} className="mx-auto mb-3 opacity-30" />
          <p>No items found</p>
        </div>
      )}
    </div>
  );
}
