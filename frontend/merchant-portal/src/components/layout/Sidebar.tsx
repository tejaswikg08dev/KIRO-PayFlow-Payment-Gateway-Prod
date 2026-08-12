import { NavLink } from 'react-router-dom';

const navItems = [
  { path: '/dashboard', label: 'Dashboard', icon: '📊' },
  { path: '/transactions', label: 'Transactions', icon: '💳' },
  { path: '/analytics', label: 'Analytics', icon: '📈' },
  { path: '/api-keys', label: 'API Keys', icon: '🔑' },
  { path: '/webhooks', label: 'Webhooks', icon: '🔗' },
  { path: '/settlements', label: 'Settlements', icon: '🏦' },
  { path: '/settings', label: 'Settings', icon: '⚙️' },
];

export function Sidebar() {
  return (
    <aside className="w-64 bg-white border-r border-gray-200 min-h-screen p-4">
      <nav className="space-y-1">
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            className={({ isActive }) =>
              `flex items-center gap-3 px-4 py-3 rounded-lg text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-primary-50 text-primary-700'
                  : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
              }`
            }
          >
            <span>{item.icon}</span>
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
    </aside>
  );
}
