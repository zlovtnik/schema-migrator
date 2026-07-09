import React from 'react';
import { Database, CheckCircle, AlertCircle, Table } from 'lucide-react';

interface SchemaObject {
  id: string;
  name: string;
  type: string;
  status: 'synced' | 'drift' | 'pending';
  schema: string;
  lastUpdated: string;
}

const mockData: SchemaObject[] = [
  { id: '1', name: 'users', type: 'Table', status: 'synced', schema: 'public', lastUpdated: '2 hours ago' },
  { id: '2', name: 'orders', type: 'Table', status: 'synced', schema: 'public', lastUpdated: '3 hours ago' },
  { id: '3', name: 'products', type: 'Table', status: 'drift', schema: 'public', lastUpdated: '1 day ago' },
  { id: '4', name: 'user_idx_email', type: 'Index', status: 'synced', schema: 'public', lastUpdated: '2 hours ago' },
  { id: '5', name: 'order_idx_user', type: 'Index', status: 'synced', schema: 'public', lastUpdated: '3 hours ago' },
  { id: '6', name: 'calculate_total', type: 'Function', status: 'synced', schema: 'public', lastUpdated: '5 hours ago' },
  { id: '7', name: 'update_timestamp', type: 'Function', status: 'pending', schema: 'public', lastUpdated: '1 day ago' },
  { id: '8', name: 'uuid-ossp', type: 'Extension', status: 'synced', schema: 'public', lastUpdated: '1 week ago' },
];

export function SchemaTable() {
  const getStatusBadge = (status: SchemaObject['status']) => {
    switch (status) {
      case 'synced':
        return (
          <span className="flex items-center gap-1.5 px-2.5 py-1 bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 rounded-full text-xs font-medium">
            <CheckCircle className="w-3.5 h-3.5" />
            Synced
          </span>
        );
      case 'drift':
        return (
          <span className="flex items-center gap-1.5 px-2.5 py-1 bg-amber-500/10 text-amber-400 border border-amber-500/30 rounded-full text-xs font-medium">
            <AlertCircle className="w-3.5 h-3.5" />
            Drift
          </span>
        );
      case 'pending':
        return (
          <span className="flex items-center gap-1.5 px-2.5 py-1 bg-blue-500/10 text-blue-400 border border-blue-500/30 rounded-full text-xs font-medium">
            <AlertCircle className="w-3.5 h-3.5" />
            Pending
          </span>
        );
    }
  };

  const getTypeIcon = (type: string) => {
    switch (type.toLowerCase()) {
      case 'table':
        return <Table className="w-4 h-4" />;
      case 'index':
        return <Database className="w-4 h-4" />;
      default:
        return <Database className="w-4 h-4" />;
    }
  };

  return (
    <div className="bg-neutral-900 border border-neutral-800 rounded-xl overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead className="bg-neutral-800/50 border-b border-neutral-800">
            <tr>
              <th className="text-left text-xs font-semibold text-neutral-400 uppercase tracking-wider px-6 py-4">
                Object Name
              </th>
              <th className="text-left text-xs font-semibold text-neutral-400 uppercase tracking-wider px-6 py-4">
                Type
              </th>
              <th className="text-left text-xs font-semibold text-neutral-400 uppercase tracking-wider px-6 py-4">
                Schema
              </th>
              <th className="text-left text-xs font-semibold text-neutral-400 uppercase tracking-wider px-6 py-4">
                Status
              </th>
              <th className="text-left text-xs font-semibold text-neutral-400 uppercase tracking-wider px-6 py-4">
                Last Updated
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-800">
            {mockData.map((item) => (
              <tr key={item.id} className="hover:bg-neutral-800/50 transition-colors cursor-pointer">
                <td className="px-6 py-4">
                  <div className="flex items-center gap-3">
                    <div className="text-neutral-500">{getTypeIcon(item.type)}</div>
                    <span className="font-mono text-sm text-white">{item.name}</span>
                  </div>
                </td>
                <td className="px-6 py-4">
                  <span className="text-sm text-neutral-400">{item.type}</span>
                </td>
                <td className="px-6 py-4">
                  <span className="font-mono text-xs text-neutral-500 bg-neutral-800 px-2 py-1 rounded">
                    {item.schema}
                  </span>
                </td>
                <td className="px-6 py-4">{getStatusBadge(item.status)}</td>
                <td className="px-6 py-4">
                  <span className="text-sm text-neutral-500">{item.lastUpdated}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
