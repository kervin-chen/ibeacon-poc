# @rently/ibeacon-poc

POC React Native native module for iBeacon scanning (Android AltBeacon + iOS CoreLocation). Basic scaffolding only.

## Install

```
npm install @rently/ibeacon-poc
```

(After publishing; for local development add via relative path in example app.)

## API (initial draft)

```
interface BeaconInfo {
  uuid: string;
  major: number;
  minor: number;
  rssi: number;
  txPower?: number;
  distance?: number;
}

interface StartScanOptions {
  uuids?: string[];
  androidBackgroundBetweenScanPeriodMs?: number;
  androidBetweenScanPeriodMs?: number;
  androidScanPeriodMs?: number;
}

interface IBeaconModule {
  startScanning(options?: StartScanOptions): Promise<void>;
  stopScanning(): Promise<void>;
  isScanning(): Promise<boolean>;
  getLastSeen(): Promise<BeaconInfo[]>;
  addListener(event: 'beaconsUpdated', cb: (beacons: BeaconInfo[]) => void): void;
  removeListener(event: 'beaconsUpdated', cb: (beacons: BeaconInfo[]) => void): void;
  requestAuthorization(): Promise<'authorized' | 'denied' | 'restricted' | 'notDetermined'>;
}
```

## Example Usage (React Native)

```tsx
// App.tsx
import React, { useEffect, useState } from 'react';
import { View, Text, FlatList, PermissionsAndroid, Platform } from 'react-native';
import { IBeacon, BeaconInfo } from '@rently/ibeacon-poc';

export default function App() {
  const [beacons, setBeacons] = useState<BeaconInfo[]>([]);
  const [authorized, setAuthorized] = useState<string>('');

  useEffect(() => {
    async function init() {
      if (Platform.OS === 'android') {
        // Request required runtime permissions for Android 12+
        const perms = [
          'android.permission.ACCESS_FINE_LOCATION',
          'android.permission.BLUETOOTH_SCAN',
          'android.permission.BLUETOOTH_CONNECT'
        ];
        for (const p of perms) {
          try { await PermissionsAndroid.request(p as any); } catch {}
        }
      } else {
        const status = await IBeacon.requestAuthorization();
        setAuthorized(status);
      }
      await IBeacon.startScanning({ uuids: ['YOUR-BEACON-UUID-GOES-HERE'] });
      IBeacon.addListener('beaconsUpdated', (list) => setBeacons(list));
    }
    init();
    return () => { IBeacon.stopScanning(); };
  }, []);

  return (
    <View style={{ flex: 1, padding: 16 }}>
      <Text style={{ fontWeight: 'bold' }}>iBeacon Scan</Text>
      {authorized ? <Text>Auth: {authorized}</Text> : null}
      <FlatList
        data={beacons}
        keyExtractor={(b) => `${b.uuid}-${b.major}-${b.minor}`}
        renderItem={({ item }) => (
          <View style={{ paddingVertical: 6 }}>
            <Text>{item.uuid}</Text>
            <Text>Major: {item.major} Minor: {item.minor}</Text>
            <Text>RSSI: {item.rssi} Distance(m): {item.distance?.toFixed(2)}</Text>
          </View>
        )}
      />
    </View>
  );
}
```

Notes:
- Replace `YOUR-BEACON-UUID-GOES-HERE` with the Proximity UUID you want to filter; omit for all.
- Android permissions must be declared in the host app manifest (already provided by library, but ensure you include if minSdk < 31).
- Distances are raw estimates; smoothing recommended for stability.

## Roadmap
- Android: integrate AltBeacon library, expose scan periods, parsing & distance estimation.
- iOS: CoreLocation beacon ranging (CLLocationManager / CLBeaconRegion).
- Event emitter bridging.
- Distance smoothing (Kalman / running average) optional.
- 3D positioning layer (later phase).

## License
MIT
