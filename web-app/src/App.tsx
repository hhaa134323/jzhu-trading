import { useState } from 'react';
import Navbar, { type NavTabKey } from './components/Navbar';
import KlinePage from './pages/KlinePage';

export default function App() {
  const [activeTab, setActiveTab] = useState<NavTabKey>('backtest');

  return (
    <div className="app-shell">
      <Navbar activeTab={activeTab} onTabChange={setActiveTab} />
      <main className="container-fluid py-4 py-lg-5">
        {activeTab === 'backtest' && <KlinePage />}
      </main>
    </div>
  );
}
