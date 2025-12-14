import 'package:flutter/services.dart';

enum FlutterBlueLinkLayerState {
  idle,
  connecting,
  connected,
}

enum FlutterBlueL2capState {
  idle,
  connecting,
  connected,
}

enum FlutterBlueBondState {
  notBonded,
  bonding,
  bonded
}

enum FlutterBlueEncryptionState {
  notEncrypted,
  encrypting,
  encrypted
}

/// Defines Bluetooth protocol layers
enum FlutterBlueLayer {
  gap,
  gatt,
  l2cap,
}

/// GAP (Generic Access Profile) event types
enum FlutterBlueGapEvent {
  connected,
  disconnected,
  encryptionStateChanged,
  bondStateChanged,
  channelSoundingOpened,
  channelSoundingStarted,
  channelSoundingStopped,
  channelSoundingClosed,
  channelSoundingDataReady
}

/// GATT (Generic Attribute Profile) event types
enum FlutterBlueGattEvent {
  servicesDiscovered,
  read,
  write,
}

/// L2CAP (Logical Link Control and Adaptation Protocol) event types
enum FlutterBlueL2capEvent {
  connected,
  disconnected,
  dataReceived,
  dataSent,
}

class FlutterBlueDevice {
  final String name;
  final String bluetoothAddress;
  List<int>? advData;
  int? rssi;
  FlutterBlueLinkLayerState linkLayerState;
  FlutterBlueL2capState l2capState;
  FlutterBlueBondState bondState;
  FlutterBlueEncryptionState encryptionState;


  FlutterBlueDevice({
    required this.name,
    required this.bluetoothAddress,
    required this.advData,
    required this.linkLayerState,
    required this.l2capState,
    required this.bondState,
    required this.encryptionState,
    this.rssi
  });

  static FlutterBlueLinkLayerState _parseLinkLayerState(String? state) {
    switch (state) {
      case 'idle':
        return FlutterBlueLinkLayerState.idle;
      case 'connecting':
        return FlutterBlueLinkLayerState.connecting;
      case 'connected':
        return FlutterBlueLinkLayerState.connected;
      default:
        return FlutterBlueLinkLayerState.idle;
    }
  }

  static FlutterBlueL2capState _parseL2capState(String? state) {
    switch (state) {
      case 'idle':
        return FlutterBlueL2capState.idle;
      case 'connecting':
        return FlutterBlueL2capState.connecting;
      case 'connected':
        return FlutterBlueL2capState.connected;
      default:
        return FlutterBlueL2capState.idle;
    }
  }

  static FlutterBlueBondState _parseBondState(String? bondState) {
    switch(bondState) {
      case "notBonded":
        return FlutterBlueBondState.notBonded;
      case "bonding":
        return FlutterBlueBondState.bonding;
      case "bonded":
        return FlutterBlueBondState.bonded;
      default:
        return FlutterBlueBondState.notBonded;
    }
  }

  static FlutterBlueEncryptionState _parseEncryptionState(String? encryptionState) {
    switch (encryptionState) {
      case "notEncrypted":
        return FlutterBlueEncryptionState.notEncrypted;
      case "encrypting":
        return FlutterBlueEncryptionState.encrypting;
      case "encrypted":
        return FlutterBlueEncryptionState.encrypted;
      default:
        return FlutterBlueEncryptionState.notEncrypted;
    }
  }

  factory FlutterBlueDevice.fromMap(Map<dynamic, dynamic> map) {
    final advRaw = map['advData'];
    final advData = (advRaw is List)
        ? advRaw.map((e) => (e is int) ? e : 0).toList()
        : <int>[];

    return FlutterBlueDevice(
      name: map['name'] ?? 'Unnamed',
      bluetoothAddress: map['bluetoothAddress'],
      advData: advData,
      linkLayerState: _parseLinkLayerState(map['linkLayerState']),
      l2capState:  _parseL2capState(map['l2capState']),
      bondState: _parseBondState(map['bondState']),
      encryptionState: _parseEncryptionState(map['encryptionState']),
      rssi: map['rssi'],
    );
  }
}




/// Represents a structured Bluetooth event
class FlutterBlueEvent {
  final FlutterBlueLayer layer;
  final Object event; // Must be one of: GapEvent, GattEvent, L2capEvent
  final String bluetoothAddress;
  final FlutterBlueDevice deviceInfo;
  final Uint8List? data;
  final double? dataChannelSounding;

  FlutterBlueEvent({
    required this.layer,
    required this.event,
    required this.bluetoothAddress,
    required this.deviceInfo,
    this.data,
    this.dataChannelSounding
  });

  factory FlutterBlueEvent.fromMap(Map<String, dynamic> map) {
    final layer = _parseLayer(map['layer']);
    final event = _parseEvent(layer, map['event']);
    final FlutterBlueDevice deviceInfo = FlutterBlueDevice.fromMap(Map<String, dynamic>.from(map['deviceInfo']));

    return FlutterBlueEvent(
      layer: layer,
      event: event,
      bluetoothAddress: map['bluetoothAddress'],
      data: map['data'] as Uint8List?,
      dataChannelSounding: map['dataChannelSounding'] != null ? (map['dataChannelSounding'] as num).toDouble() : null,
      deviceInfo: deviceInfo
    );
  }

  static FlutterBlueLayer _parseLayer(String? str) {
    switch (str) {
      case 'gap': return FlutterBlueLayer.gap;
      case 'gatt': return FlutterBlueLayer.gatt;
      case 'l2cap': return FlutterBlueLayer.l2cap;
      default: throw Exception('Unknown layer: $str');
    }
  }

  static Object _parseEvent(FlutterBlueLayer layer, String? str) {
    switch (layer) {
      case FlutterBlueLayer.gap:
        switch (str) {
          case 'connected': return FlutterBlueGapEvent.connected;
          case 'disconnected': return FlutterBlueGapEvent.disconnected;
          case 'bondStateChanged': return FlutterBlueGapEvent.bondStateChanged;
          case 'encryptionStateChanged': return FlutterBlueGapEvent.encryptionStateChanged;
          case 'channelSoundingOpened': return FlutterBlueGapEvent.channelSoundingOpened;
          case 'channelSoundingStarted': return FlutterBlueGapEvent.channelSoundingStarted;
          case 'channelSoundingStopped': return FlutterBlueGapEvent.channelSoundingStopped;
          case 'channelSoundingClosed': return FlutterBlueGapEvent.channelSoundingClosed;
          case 'channelSoundingDataReady': return FlutterBlueGapEvent.channelSoundingDataReady;
        }
        break;

      case FlutterBlueLayer.gatt:
        switch (str) {
          case 'servicesDiscovered': return FlutterBlueGattEvent.servicesDiscovered;
          case 'read': return FlutterBlueGattEvent.read;
          case 'write': return FlutterBlueGattEvent.write;
        }
        break;

      case FlutterBlueLayer.l2cap:
        switch (str) {
          case 'connected': return FlutterBlueL2capEvent.connected;
          case 'disconnected': return FlutterBlueL2capEvent.disconnected;
          case 'dataReceived': return FlutterBlueL2capEvent.dataReceived;
          case 'dataSent': return FlutterBlueL2capEvent.dataSent;
        }
        break;
    }
    throw Exception('Unknown event: $str for layer: $layer');
  }
}

class FlutterBlueConnect {
  static const MethodChannel _channel = MethodChannel('flutter_blue_connect');
  static const EventChannel _scanChannel  = EventChannel('flutter_blue_connect_scan');
  static const EventChannel _bluetoothEventChannel = EventChannel('channel_bluetooth_events');

  Future<String?> getPlatformVersion() {
    return _channel.invokeMethod<String>('getPlatformVersion');
  }

  static Future<void> printAllBluetoothMethods() async {
    await _channel.invokeMethod<Map>('printAllBluetoothMethods');
  }

  static Stream<List<FlutterBlueDevice>> get scanResults {
    return _scanChannel.receiveBroadcastStream().map((event) {
      if (event is List) {
        return event.map((device) => FlutterBlueDevice.fromMap(Map<String, dynamic>.from(device))).toList();
      } else {
        return [];
      }
    });
  }

  static Stream<FlutterBlueEvent> get bluetoothEvents {
    return _bluetoothEventChannel.receiveBroadcastStream().map((event) {
      if (event is Map) {
        return FlutterBlueEvent.fromMap(Map<String, dynamic>.from(event));
      } else {
        throw Exception("Invalid FlutterBlueEvent payload");
      }
    });
  }

  static Future<void> startScan({
    int refreshTimeMs = 500
  }) async {
    await _channel.invokeMethod('startScan',{
      'refreshTimeMs': refreshTimeMs
    });
  }

  static Future<void> stopScan() async {
    await _channel.invokeMethod('stopScan');
  }

  static Future<void> connect(String bluetoothAddress, {int timeout = 10000}) async {
    await _channel.invokeMethod('connect', {
      'bluetoothAddress': bluetoothAddress,
      'timeout': timeout,
    });
  }

  static Future<void> disconnect(String bluetoothAddress) async {
    await _channel.invokeMethod('disconnect', {
      'bluetoothAddress': bluetoothAddress,
    });
  }

  static Future<void> l2capChannelOpen({
    required String bluetoothAddress,
    required int psm,
    int mtu = 23, // optional if your platform allows configuring
    bool secure = false,
    int timeout = 10000,
  }) async {
    await _channel.invokeMethod('l2capChannelOpen', {
      'bluetoothAddress': bluetoothAddress,
      'psm': psm,
      'mtu': mtu,
      'secure': secure,
      'timeout': timeout,
    });
  }

  static Future<void> l2capChannelClose({required String bluetoothAddress}) async {
    await _channel.invokeMethod("l2capChannelClose", {
      "bluetoothAddress": bluetoothAddress,
    });
  }

  static Future<void> l2capSend({
    required String bluetoothAddress,
    required Uint8List data,
  }) async {
    await _channel.invokeMethod('l2capSend', {
      'bluetoothAddress': bluetoothAddress,
      'data': data,
    });
  }

  static Future<Map<String, dynamic>?> generateLocalLeScOobData() async {
    final data = await _channel.invokeMethod<Map>('generateLocalLeScOobData');
    if (data != null) {

    }
    return data?.map((k, v) => MapEntry(k, v.toString()));
  }

  static Future<Map<String, dynamic>> getLocalLeScOobData() async {
    final Map res = await _channel.invokeMethod('getLocalLeScOobData');
    return {
      'address': (res['address'] as String),
      'confirm': res['confirm'] as Uint8List,
      'random': res['random'] as Uint8List,
    };
  }

  static Future<void> setPeerLeScOobData({
    required String bluetoothAddress,
    required Uint8List confirmValue,
    required Uint8List randomNumber
  }) async {
    await _channel.invokeMethod('setPeerLeScOobData', {
      'address': bluetoothAddress,
      'confirm': confirmValue,
      'random': randomNumber
    });
  }

  static Future<void> startPairing({required String bluetoothAddress}) async {
    await _channel.invokeMethod('startPairing', {
      'bluetoothAddress': bluetoothAddress,
    });
  }

  static Future<void> startPairingOob({
    required String bluetoothAddress
  }) async {
    await _channel.invokeMethod('startPairingOob', {
      'bluetoothAddress': bluetoothAddress,
    });
  }

  static Future<void> deleteBond({required String bluetoothAddress}) async {
    await _channel.invokeMethod('deleteBond', {
      'bluetoothAddress': bluetoothAddress
    });
  }

  static Future<void> startEncryptConnection({required String bluetoothAddress}) async {
    await _channel.invokeMethod('startEncryptConnection', {
      'bluetoothAddress': bluetoothAddress
    });
  }

  static Future<void> startChannelSoundingInitiator(String address) async {
    await _channel.invokeMethod('startChannelSoundingInitiator', {
      'bluetoothAddress': address
    });
  }

  static Future<void> stopChannelSoundingInitiator(String address) async {
    await _channel.invokeMethod('stopChannelSoundingInitiator', {
      'bluetoothAddress': address
    });
  }

  static Future<void> startChannelSoundingReflector(String address) async {
    await _channel.invokeMethod('startChannelSoundingReflector', {
      'bluetoothAddress': address
    });
  }

  static Future<void> stopChannelSoundingReflector(String address) async {
    await _channel.invokeMethod('stopChannelSoundingReflector', {
      'bluetoothAddress': address
    });
  }
}