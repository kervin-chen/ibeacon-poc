// Minimal shims so library can type-check outside a React Native project.
declare module 'react-native' {
  export const NativeModules: any;
  export class NativeEventEmitter {
    constructor(nativeModule?: any);
    addListener(event: string, listener: (...args: any[]) => void): { remove: () => void };
    removeListener(event: string, listener: (...args: any[]) => void): void;
  }
  export const Platform: { OS: string };
}
