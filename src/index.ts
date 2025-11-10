// Public TypeScript interface for the native module
// Basic structure only; actual implementation will follow.

import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

export interface BeaconInfo {
  uuid: string;
  major: number;
  minor: number;
  rssi: number;
  txPower?: number;
  distance?: number; // estimated meters
}

export interface StartScanOptions {
  uuids?: string[]; // filter by proximity UUIDs
  androidBackgroundBetweenScanPeriodMs?: number; // optional tuning
  androidBetweenScanPeriodMs?: number; // optional tuning
  androidScanPeriodMs?: number; // optional tuning
}

export interface IBeaconModule {
  startScanning(options?: StartScanOptions): Promise<void>;
  stopScanning(): Promise<void>;
  isScanning(): Promise<boolean>;
  getLastSeen(): Promise<BeaconInfo[]>;
  addListener(event: 'beaconsUpdated', callback: (beacons: BeaconInfo[]) => void): void;
  removeListener(event: 'beaconsUpdated', callback: (beacons: BeaconInfo[]) => void): void;
  // iOS specific for authorization
  requestAuthorization(): Promise<'authorized' | 'denied' | 'restricted' | 'notDetermined'>;
}

// Anchor definition (known fixed beacon positions)
export interface BeaconAnchor {
  uuid: string;
  major: number;
  minor: number;
  x: number;
  y: number;
  z: number; // meters
  txPower?: number; // calibrated Tx power at 1m if available --TX power (transmit power) refers to the strength of the outgoing signal transmitted by an IoT device
}

export interface Position3D {
  x: number; y: number; z: number;
  anchorsUsed: number;
  residual?: number; // sum squared error
  quality?: 'low' | 'medium' | 'high';
}

export interface Position2D { x: number; y: number; anchorsUsed: number; residual?: number; quality?: 'low' | 'medium' | 'high'; }

const native = NativeModules.IBeaconPoc as any;
const emitter = new NativeEventEmitter(native);
let subscription: any;
let cached: BeaconInfo[] = [];
let anchors: BeaconAnchor[] = [];
const emaDistances: Record<string, number> = {}; // exponential moving average per beacon key
const emaAlpha = 0.35; // smoothing factor

export const IBeacon: IBeaconModule = {
  async startScanning(options) {
    if (!native) throw new Error('Native module not linked');
    if (!subscription) {
      subscription = emitter.addListener('beaconsUpdated', (beacons: BeaconInfo[]) => {
        cached = beacons;
      });
    }
    await native.startScanning(options || {});
  },
  async stopScanning() {
    if (subscription) { subscription.remove(); subscription = null; }
    await native.stopScanning();
  },
  async isScanning() { return native.isScanning(); },
  async getLastSeen() { return cached; },
  addListener(event, callback) {
    emitter.addListener(event, callback);
  },
  removeListener(event, callback) {
    emitter.removeListener(event, callback as any);
  },
  async requestAuthorization() {
    if (Platform.OS === 'ios') return native.requestAuthorization();
    return 'authorized';
  }
};

export function setAnchors(a: BeaconAnchor[]) { anchors = a.slice(); }

function beaconKey(b: BeaconInfo) { return `${b.uuid}-${b.major}-${b.minor}`; }

function smoothDistance(raw: number, key: string): number {
  if (!isFinite(raw) || raw <= 0) return raw;
  const prev = emaDistances[key];
  const val = prev == null ? raw : (emaAlpha * raw + (1 - emaAlpha) * prev);
  emaDistances[key] = val;
  return val;
}

function rssiToDistance(rssi: number, txPower: number = -59, pathLossExponent: number = 2.0): number {
  // d = 10 ^ ((TxPower - RSSI) / (10 * n)) ; TxPower is RSSI at 1m.
  return Math.pow(10, (txPower - rssi) / (10 * pathLossExponent));
}

export function computePosition3D(pathLossExponent = 2.0): Position3D | Position2D | null {
  // Match beacons to anchors and build distance list
  const usable: { a: BeaconAnchor; d: number }[] = [];
  for (const an of anchors) {
    const b = cached.find(c => c.uuid === an.uuid && c.major === an.major && c.minor === an.minor);
    if (!b) continue;
    let dist = b.distance != null && b.distance! > 0 ? b.distance! : rssiToDistance(b.rssi, an.txPower ?? b.txPower ?? -59, pathLossExponent);
    dist = smoothDistance(dist, beaconKey(b));
    if (isFinite(dist) && dist > 0) usable.push({ a: an, d: dist });
  }
  if (usable.length < 4) {
    // Fallback to 2D if we have at least 3 anchors
    if (usable.length >= 3) {
      return computePosition2D(usable);
    }
    return null;
  }

  // Use first as reference for linearization
  const ref = usable[0];
  const others = usable.slice(1);
  const A: number[][] = [];
  const bVec: number[] = [];
  for (const u of others) {
    const { a: ai, d: di } = u;
    const { a: a0, d: d0 } = ref;
    // (xi - x)^2 + (yi - y)^2 + (zi - z)^2 - (x0 - x)^2 - ... = di^2 - d0^2 + xi^2 - x0^2 + yi^2 - y0^2 + zi^2 - z0^2
    // Rearranged linear in x,y,z: 2(x0 - xi)x + 2(y0 - yi)y + 2(z0 - zi)z = di^2 - d0^2 + xi^2 - x0^2 + yi^2 - y0^2 + zi^2 - z0^2
    const x0 = a0.x, y0 = a0.y, z0 = a0.z;
    const xi = ai.x, yi = ai.y, zi = ai.z;
    A.push([2*(x0 - xi), 2*(y0 - yi), 2*(z0 - zi)]);
    bVec.push(di*di - d0*d0 + xi*xi - x0*x0 + yi*yi - y0*y0 + zi*zi - z0*z0);
  }
  const AT = transpose(A);
  const ATA = multiply(AT, A);
  const ATb = multiplyVector(AT, bVec);
  const inv = invert3x3(ATA);
  if (!inv) return null;
  const sol = multiplyVector(inv, ATb);

  // Residual error
  let residual = 0;
  for (const u of usable) {
    const dx = sol[0] - u.a.x; const dy = sol[1] - u.a.y; const dz = sol[2] - u.a.z;
    const est = Math.sqrt(dx*dx + dy*dy + dz*dz);
    residual += (est - u.d) * (est - u.d);
  }
  const quality = residual < 1 ? 'high' : residual < 4 ? 'medium' : 'low';
  return { x: sol[0], y: sol[1], z: sol[2], anchorsUsed: usable.length, residual, quality };
}

function computePosition2D(usable: { a: BeaconAnchor; d: number }[]): Position2D | null {
  // Use first as reference
  const ref = usable[0];
  const others = usable.slice(1);
  const A: number[][] = [];
  const bVec: number[] = [];
  for (const u of others) {
    const { a: ai, d: di } = u; const { a: a0, d: d0 } = ref;
    const x0 = a0.x, y0 = a0.y; const xi = ai.x, yi = ai.y;
    // 2(x0 - xi)x + 2(y0 - yi)y = di^2 - d0^2 + xi^2 - x0^2 + yi^2 - y0^2
    A.push([2*(x0 - xi), 2*(y0 - yi)]);
    bVec.push(di*di - d0*d0 + xi*xi - x0*x0 + yi*yi - y0*y0);
  }
  // Least squares: (A^T A)^{-1} A^T b
  const AT = transpose(A);
  const ATA = multiply(AT, A); // 2x2
  const inv2 = invert2x2(ATA);
  if (!inv2) return null;
  const ATb = multiplyVector(AT, bVec);
  const sol = multiplyVector(inv2, ATb);
  // Residual
  let residual = 0;
  for (const u of usable) {
    const dx = sol[0] - u.a.x; const dy = sol[1] - u.a.y;
    const est = Math.sqrt(dx*dx + dy*dy);
    residual += (est - u.d) * (est - u.d);
  }
  const quality = residual < 1 ? 'high' : residual < 4 ? 'medium' : 'low';
  return { x: sol[0], y: sol[1], anchorsUsed: usable.length, residual, quality };
}

function invert2x2(m: number[][]): number[][] | null {
  if (m.length !== 2 || m[0].length !== 2) return null;
  const [[a,b],[c,d]] = m; const det = a*d - b*c; if (Math.abs(det) < 1e-12) return null;
  return [[ d/det, -b/det ], [ -c/det, a/det ]];
}

export async function getEstimatedPosition3D(): Promise<Position3D | Position2D | null> { return computePosition3D(); }
// Also provide generic alias
export async function getEstimatedPosition(): Promise<Position3D | Position2D | null> { return computePosition3D(); }

// Linear algebra helpers
function transpose(m: number[][]): number[][] { return m[0].map((_, i) => m.map(r => r[i])); }
function multiply(a: number[][], b: number[][]): number[][] { return a.map(row => b[0].map((_, j) => row.reduce((s, v, k) => s + v * b[k][j], 0))); }
function multiplyVector(a: number[][], v: number[]): number[] { return a.map(row => row.reduce((s, val, i) => s + val * v[i], 0)); }
function invert3x3(m: number[][]): number[][] | null {
  if (m.length !== 3 || m[0].length !== 3) return null;
  const [[a,b,c],[d,e,f],[g,h,i]] = m;
  const A = e*i - f*h; const B = -(d*i - f*g); const C = d*h - e*g;
  const D = -(b*i - c*h); const E = a*i - c*g; const F = -(a*h - b*g);
  const G = b*f - c*e; const H = -(a*f - c*d); const I = a*e - b*d;
  const det = a*A + b*B + c*C;
  if (Math.abs(det) < 1e-9) return null;
  return [ [A/det, D/det, G/det], [B/det, E/det, H/det], [C/det, F/det, I/det] ];
}
