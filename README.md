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

## 2D / 3D Positioning (POC)

Add known beacon anchors (with fixed coordinates) and retrieve estimated position.

```ts
import { setAnchors, getEstimatedPosition } from '@rently/ibeacon-poc';

// Define anchors (meters in a local coordinate system)
setAnchors([
  { uuid: 'E2C56DB5-DFFB-48D2-B060-D0F5A71096E0', major: 1, minor: 1, x: 0,  y: 0,  z: 1.2 },
  { uuid: 'E2C56DB5-DFFB-48D2-B060-D0F5A71096E0', major: 1, minor: 2, x: 5,  y: 0,  z: 1.2 },
  { uuid: 'E2C56DB5-DFFB-48D2-B060-D0F5A71096E0', major: 1, minor: 3, x: 0,  y: 5,  z: 1.2 },
  { uuid: 'E2C56DB5-DFFB-48D2-B060-D0F5A71096E0', major: 1, minor: 4, x: 5,  y: 5,  z: 1.2 },
]);

// Periodically (e.g. every second) call:
const pos = await getEstimatedPosition();
if (pos) {
  if ('z' in pos) {
    console.log('3D position', pos.x, pos.y, pos.z, 'quality', pos.quality);
  } else {
    console.log('2D position', pos.x, pos.y, 'quality', pos.quality);
  }
}
```

Notes:
- Requires distances from at least 4 anchors for 3D; returns 2D if only 3.
- Distances are smoothed (EMA). Further filtering (Kalman) can be added.
- Coordinate system is arbitrary local reference (choose an origin and units in meters).

### Optional Extended Advertising Scanner (Android Only)

Use when firmware only emits iBeacon payload via Bluetooth 5 extended advertising. Not supported on iOS.

```ts
import { IBeaconExtendedScanner } from '@rently/ibeacon-poc';

if (IBeaconExtendedScanner) {
  await IBeaconExtendedScanner.start({ legacyOnly: false });
  IBeaconExtendedScanner.addListener('extendedBeaconsUpdated', (beacons) => {
    console.log('Extended beacons', beacons);
  });
}
```

## Stopping Scans

Main iBeacon ranging:
```ts
// Stop ranging (remove listener if you added custom one)
IBeacon.stopScanning();
```
Extended advertising scanner (Android only):
```ts
if (IBeaconExtendedScanner) {
  IBeaconExtendedScanner.stop();
}
```
Call these in component unmount (React useEffect cleanup) or when you no longer need updates to conserve battery.

Remove by deleting the `IBeaconExtendedScannerModule.kt` and its registration in `IBeaconPocPackage`.

## Roadmap
- Android: integrate AltBeacon library, expose scan periods, parsing & distance estimation.
- iOS: CoreLocation beacon ranging (CLLocationManager / CLBeaconRegion).
- Event emitter bridging.
- Distance smoothing (Kalman / running average) optional.
- 3D positioning layer (later phase).

## License
MIT
