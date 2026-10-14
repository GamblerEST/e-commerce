import { Link } from 'react-router-dom';
import { COMPANY } from '../companyInfo';

export default function Footer() {
  return (
    <footer className="site-footer">
      <div className="footer-section">
        <strong>{COMPANY.name}</strong>
        <span>{COMPANY.address}</span>
        <a href={`mailto:${COMPANY.email}`}>{COMPANY.email}</a>
        <a href={`tel:${COMPANY.phone.replace(/\s/g, '')}`}>{COMPANY.phone}</a>
      </div>
      <nav className="footer-links">
        <Link to="/privacy">Privacy policy</Link>
        <Link to="/sales-conditions">Sales conditions</Link>
        <Link to="/contacts">Contact us</Link>
      </nav>
    </footer>
  );
}
