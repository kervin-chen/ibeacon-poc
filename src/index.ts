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

const native = NativeModules.IBeaconPoc as any;
const emitter = new NativeEventEmitter(native);
let subscription: any;
let cached: BeaconInfo[] = [];

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
