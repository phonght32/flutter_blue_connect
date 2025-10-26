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
  bondStateChanged
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
  final int? rssi;
  final FlutterBlueLinkLayerState? linkLayerState;

  FlutterBlueDevice({required this.name, required this.bluetoothAddress, required this.linkLayerState, this.rssi});

  factory FlutterBlueDevice.fromMap(Map<dynamic, dynamic> map) {
    return FlutterBlueDevice(
      name: map['name'] ?? 'Unknown',
      bluetoothAddress: map['bluetoothAddress'],
      linkLayerState: map['linkLayerState'],
      rssi: map['rssi'],
    );
  }
}

/// Represents a structured Bluetooth event
class FlutterBlueEvent {
  final FlutterBlueLayer layer;
  final Object event; // Must be one of: GapEvent, GattEvent, L2capEvent
  final String bluetoothAddress;
  final FlutterBlueBondState bondState;
  final FlutterBlueEncryptionState encryptState;
  final String? uuid;
  final List<int>? value;
  final int? status;
  final int? psm;
  final Uint8List? data;
  final List<String>? services;

  FlutterBlueEvent({
    required this.layer,
    required this.event,
    required this.bluetoothAddress,
    required this.bondState,
    required this.encryptState,
    this.uuid,
    this.value,
    this.status,
    this.psm,
    this.data,
    this.services,
  });

  factory FlutterBlueEvent.fromMap(Map<String, dynamic> map) {
    final layer = _parseLayer(map['layer']);
    final event = _parseEvent(layer, map['event']);
    final FlutterBlueBondState bondState = _parseBondState(map['bondState']);
    final encryptState = _parseEncryptionState(map['encryptState']);
    return FlutterBlueEvent(
      layer: layer,
      event: event,
      bluetoothAddress: map['bluetoothAddress'],
      bondState: bondState,
      encryptState: encryptState,
      uuid: map['uuid'],
      value: map['value']?.cast<int>(),
      status: map['status'],
      psm: map['psm'],
      data: map['data'] as Uint8List?,
      services: (map['services'] as List?)?.cast<String>(),
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

  static FlutterBlueEncryptionState _parseEncryptionState(String? encryptState) {
    switch (encryptState) {
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
}

class FlutterBlueConnect {
  static const MethodChannel _channel = MethodChannel('flutter_blue_connect');
  static const EventChannel _scanChannel  = EventChannel('flutter_blue_connect_scan');
  static const EventChannel _bluetoothEventChannel = EventChannel('channel_bluetooth_events');

  Future<String?> getPlatformVersion() {
    return _channel.invokeMethod<String>('getPlatformVersion');
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

  static Future<void> l2capSend({
    required String bluetoothAddress,
    required Uint8List data,
  }) async {
    await _channel.invokeMethod('l2capSend', {
      'bluetoothAddress': bluetoothAddress,
      'data': data,
    });
  }

  static Future<void> startPairing({required String bluetoothAddress}) async {
    await _channel.invokeMethod('startPairing', {
      'bluetoothAddress': bluetoothAddress,
    });
  }
}