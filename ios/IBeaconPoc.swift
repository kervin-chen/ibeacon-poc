import Foundation
import CoreLocation
import React

@objc(IBeaconPoc)
class IBeaconPoc: RCTEventEmitter, CLLocationManagerDelegate {
  private let locationManager = CLLocationManager()
  private var region: CLBeaconRegion?
  private var scanning = false
  private var lastSeen: [CLBeacon] = []

  override init() {
    super.init()
    locationManager.delegate = self
  }

  override static func requiresMainQueueSetup() -> Bool { true }
  override func supportedEvents() -> [String]! { ["beaconsUpdated"] }

  @objc func startScanning(_ options: NSDictionary?, resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    if scanning { resolve(nil); return }
    if CLLocationManager.authorizationStatus() == .notDetermined {
      locationManager.requestWhenInUseAuthorization()
    }
    if let uuids = options?["uuids"] as? [String], let first = uuids.first, let uuid = UUID(uuidString: first) {
      region = CLBeaconRegion(uuid: uuid, identifier: "IBeaconPocRegion")
    } else {
      // Wildcard region cannot be created without UUID; leave nil and handle ranging whole
    }
    locationManager.startRangingBeacons(satisfying: CLBeaconIdentityConstraint(uuid: region?.uuid ?? UUID()))
    scanning = true
    resolve(nil)
  }

  @objc func stopScanning(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    if !scanning { resolve(nil); return }
    if let r = region {
      locationManager.stopRangingBeacons(satisfying: CLBeaconIdentityConstraint(uuid: r.uuid))
    }
    scanning = false
    resolve(nil)
  }

  @objc func isScanning(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) { resolve(scanning) }

  @objc func getLastSeen(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) { 
    let arr: [[String: Any]] = lastSeen.map { b in [
      "uuid": b.uuid.uuidString,
      "major": b.major.intValue,
      "minor": b.minor.intValue,
      "rssi": b.rssi,
      "distance": b.accuracy
    ] }
    resolve(arr)
  }

  @objc func requestAuthorization(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    let status = CLLocationManager.authorizationStatus()
    if status == .notDetermined { locationManager.requestWhenInUseAuthorization() }
    resolve(statusString(status))
  }

  func statusString(_ status: CLAuthorizationStatus) -> String {
    switch status {
    case .authorizedAlways, .authorizedWhenInUse: return "authorized"
    case .denied: return "denied"
    case .restricted: return "restricted"
    case .notDetermined: return "notDetermined"
    @unknown default: return "denied"
    }
  }

  func locationManager(_ manager: CLLocationManager, didRange beacons: [CLBeacon], satisfying constraint: CLBeaconIdentityConstraint) {
    lastSeen = beacons
    let arr: [[String: Any]] = beacons.map { b in
      [
        "uuid": b.uuid.uuidString,
        "major": b.major.intValue,
        "minor": b.minor.intValue,
        "rssi": b.rssi,
        "distance": b.accuracy
      ]
    }
    sendEvent(withName: "beaconsUpdated", body: arr)
  }
}
