Pod::Spec.new do |s|
  s.name         = "IBeaconPoc"
  s.version      = "0.0.1"
  s.summary      = "POC React Native iBeacon scanning module"
  s.homepage     = "https://github.com/rently/ibeacon-poc"
  s.license      = "MIT"
  s.author       = { "Rently" => "dev@rently.com" }
  s.source       = { :git => "https://github.com/rently/ibeacon-poc.git", :tag => s.version.to_s }

  s.platforms    = { :ios => "13.0" }
  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.requires_arc = true

  s.dependency 'React-Core'
end
