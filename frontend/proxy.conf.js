// Dev proxy so the app can use same-origin relative URLs.
//   API_PORT=8085 npm start        (backend port; default 8080)
//   GRAFANA_URL=http://localhost:3000 npm start
const api = `http://localhost:${process.env.API_PORT || 8080}`;
const grafana = process.env.GRAFANA_URL || 'http://localhost:3000';

module.exports = {
  '/api': { target: api, changeOrigin: false },
  '/grafana': { target: grafana, changeOrigin: false, ws: true },
};
