import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'flutter_blue_connect_method_channel.dart';

abstract class FlutterBlueConnectPlatform extends PlatformInterface {
  /// Constructs a FlutterBlueConnectPlatform.
  FlutterBlueConnectPlatform() : super(token: _token);

  static final Object _token = Object();

  static FlutterBlueConnectPlatform _instance = MethodChannelFlutterBlueConnect();

  /// The default instance of [FlutterBlueConnectPlatform] to use.
  ///
  /// Defaults to [MethodChannelFlutterBlueConnect].
  static FlutterBlueConnectPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [FlutterBlueConnectPlatform] when
  /// they register themselves.
  static set instance(FlutterBlueConnectPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
