import Navbar from './components/Navbar';
import KlinePage from './pages/KlinePage';

export default function App() {
  return (
    <div className="app-shell">
      <Navbar />
      <main className="container-fluid py-4 py-lg-5">
        <KlinePage />
      </main>
    </div>
  );
}
