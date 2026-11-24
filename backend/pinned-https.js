import dns from 'node:dns/promises';
import https from 'node:https';
import net from 'node:net';

const IPV4_BLOCKED = [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8],
  ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24],
  ['192.0.2.0', 24], ['192.31.196.0', 24], ['192.52.193.0', 24], ['192.88.99.0', 24], ['192.168.0.0', 16], ['192.175.48.0', 24],
  ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24],
  ['224.0.0.0', 4], ['240.0.0.0', 4]
];

function ipv4Number(value) {
  if (net.isIP(value) !== 4) return null;
  return value.split('.').reduce((n, part) => (n * 256) + Number(part), 0) >>> 0;
}

function isPublicIpv4(value) {
  const address = ipv4Number(value);
  if (address === null) return false;
  return !IPV4_BLOCKED.some(([base, bits]) => {
    const network = ipv4Number(base);
    const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
    return (address & mask) === (network & mask);
  });
}

/** Resolve once, reject mixed/private DNS answers, and pin the socket to a vetted IPv4. */
export async function resolvePinnedPublicIpv4(hostname, resolve4 = host => dns.resolve4(host)) {
  const addresses = await resolve4(hostname);
  if (!Array.isArray(addresses) || addresses.length === 0 ||
      addresses.some(address => !isPublicIpv4(address))) {
    throw new Error('Mastodon host must resolve exclusively to public IPv4 addresses');
  }
  return addresses[0];
}

/** HTTPS transport with pinned DNS, TLS hostname verification, bounded response and no redirects. */
export async function pinnedHttpsJsonRequest(urlValue, options = {}, dependencies = {}) {
  const url = new URL(urlValue);
  if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443')) throw new Error('Pinned transport accepts HTTPS on port 443 only');
  const resolve4 = dependencies.resolve4 || (host => dns.resolve4(host));
  const request = dependencies.request || https.request;
  const pinnedAddress = await resolvePinnedPublicIpv4(url.hostname, resolve4);
  const timeoutMs = options.timeoutMs || 20000;
  const maxResponseBytes = options.maxResponseBytes || 1024 * 1024;

  return new Promise((resolve, reject) => {
    let deadline;
    let settled = false;
    const clearDeadline = () => { if (deadline) clearTimeout(deadline); };
    const fail = error => {
      if (settled) return;
      settled = true;
      clearDeadline();
      reject(error);
    };
    const succeed = value => {
      if (settled) return;
      settled = true;
      clearDeadline();
      resolve(value);
    };
    const req = request(url, {
      method: options.method || 'GET',
      headers: options.headers || {},
      agent: false,
      servername: url.hostname,
      rejectUnauthorized: true,
      lookup: (_hostname, lookupOptions, callback) => {
        if (lookupOptions?.all) callback(null, [{ address: pinnedAddress, family: 4 }]);
        else callback(null, pinnedAddress, 4);
      }
    }, response => {
      const chunks = [];
      let total = 0;
      response.on('data', chunk => {
        total += chunk.length;
        if (total > maxResponseBytes) {
          req.destroy(new Error('Mastodon response exceeded the configured size limit'));
          return;
        }
        chunks.push(chunk);
      });
      response.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        succeed({
          ok: response.statusCode >= 200 && response.statusCode < 300,
          status: response.statusCode || 0,
          headers: response.headers,
          json: async () => { try { return JSON.parse(raw); } catch { return {}; } }
        });
      });
      response.on('error', fail);
    });
    // setTimeout is an inactivity timeout; this absolute deadline also stops slow-drip responses.
    deadline = setTimeout(() => req.destroy(new Error('Mastodon request exceeded its total time limit')), timeoutMs);
    req.setTimeout(timeoutMs, () => req.destroy(new Error('Mastodon request timed out')));
    req.on('error', fail);
    if (options.body !== undefined) req.write(options.body);
    req.end();
  });
}
