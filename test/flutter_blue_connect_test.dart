import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_blue_connect/flutter_blue_connect.dart';
import 'package:flutter_blue_connect/flutter_blue_connect_platform_interface.dart';
import 'package:flutter_blue_connect/flutter_blue_connect_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockFlutterBlueConnectPlatform
    with MockPlatformInterfaceMixin
    implements FlutterBlueConnectPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final FlutterBlueConnectPlatform initialPlatform = FlutterBlueConnectPlatform.instance;

  test('$MethodChannelFlutterBlueConnect is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelFlutterBlueConnect>());
  });

  test('getPlatformVersion', () async {
    FlutterBlueConnect flutterBlueConnectPlugin = FlutterBlueConnect();
    MockFlutterBlueConnectPlatform fakePlatform = MockFlutterBlueConnectPlatform();
    FlutterBlueConnectPlatform.instance = fakePlatform;

    expect(await flutterBlueConnectPlugin.getPlatformVersion(), '42');
  });
}
