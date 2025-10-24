import 'package:flutter/services.dart';

enum BluetoothConnectionState {
  idle,
  connecting,
  connected,
}

enum BluetoothBondState {
  notBonded,
  bonding,
  bonded
}

enum BluetoothEncryptionState {
  notEncrypted,
  encrypting,
  encrypted
}

/// Defines Bluetooth protocol layers
enum BluetoothLayer {
  gap,
  gatt,
  l2cap,
}

/// GAP (Generic Access Profile) event types
enum BluetoothGapEvent {
  connected,
  disconnected,
  encryptionStateChanged,
  bondStateChanged
}

/// GATT (Generic Attribute Profile) event types
enum BluetoothGattEvent {
  servicesDiscovered,
  read,
  write,
}

/// L2CAP (Logical Link Control and Adaptation Protocol) event types
enum BluetoothL2capEvent {
  connected,
  disconnected,
  dataReceived,
  dataSent,
}

class BluetoothDevice {
  final String name;
  final String bluetoothAddress;
  final int? rssi;
  final BluetoothConnectionState? connectionState;

  BluetoothDevice({required this.name, required this.bluetoothAddress, required this.connectionState, this.rssi});

  factory BluetoothDevice.fromMap(Map<dynamic, dynamic> map) {
    return BluetoothDevice(
      name: map['name'] ?? 'Unknown',
      bluetoothAddress: map['bluetoothAddress'],
      connectionState: map['connectionState'],
      rssi: map['rssi'],
    );
  }
}

/// Represents a structured Bluetooth event
class BluetoothEvent {
  final BluetoothLayer layer;
  final Object event; // Must be one of: GapEvent, GattEvent, L2capEvent
  final String bluetoothAddress;
  final BluetoothBondState bondState;
  final BluetoothEncryptionState encryptState;
  final String? uuid;
  final List<int>? value;
  final int? status;
  final int? psm;
  final Uint8List? data;
  final List<String>? services;

  BluetoothEvent({
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

  factory BluetoothEvent.fromMap(Map<String, dynamic> map) {
    final layer = _parseLayer(map['layer']);
    final event = _parseEvent(layer, map['event']);
    final BluetoothBondState bondState = _parseBondState(map['bondState']);
    final encryptState = _parseEncryptionState(map['encryptState']);
    return BluetoothEvent(
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

  static BluetoothLayer _parseLayer(String? str) {
    switch (str) {
      case 'gap': return BluetoothLayer.gap;
      case 'gatt': return BluetoothLayer.gatt;
      case 'l2cap': return BluetoothLayer.l2cap;
      default: throw Exception('Unknown layer: $str');
    }
  }

  static Object _parseEvent(BluetoothLayer layer, String? str) {
    switch (layer) {
      case BluetoothLayer.gap:
        switch (str) {
          case 'connected': return BluetoothGapEvent.connected;
          case 'disconnected': return BluetoothGapEvent.disconnected;
          case 'bondStateChanged': return BluetoothGapEvent.bondStateChanged;
        }
        break;

      case BluetoothLayer.gatt:
        switch (str) {
          case 'servicesDiscovered': return BluetoothGattEvent.servicesDiscovered;
          case 'read': return BluetoothGattEvent.read;
          case 'write': return BluetoothGattEvent.write;
        }
        break;

      case BluetoothLayer.l2cap:
        switch (str) {
          case 'connected': return BluetoothL2capEvent.connected;
          case 'disconnected': return BluetoothL2capEvent.disconnected;
          case 'dataReceived': return BluetoothL2capEvent.dataReceived;
          case 'dataSent': return BluetoothL2capEvent.dataSent;
        }
        break;
    }
    throw Exception('Unknown event: $str for layer: $layer');
  }

  static BluetoothBondState _parseBondState(String? bondState) {
    switch(bondState) {
      case "notBonded":
        return BluetoothBondState.notBonded;
      case "bonding":
        return BluetoothBondState.bonding;
      case "bonded":
        return BluetoothBondState.bonded;
      default:
        return BluetoothBondState.notBonded;
    }
  }

  static BluetoothEncryptionState _parseEncryptionState(String? encryptState) {
    switch (encryptState) {
      case "notEncrypted":
        return BluetoothEncryptionState.notEncrypted;
      case "encrypting":
        return BluetoothEncryptionState.encrypting;
      case "encrypted":
        return BluetoothEncryptionState.encrypted;
      default:
        return BluetoothEncryptionState.notEncrypted;
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

  static Stream<List<BluetoothDevice>> get scanResults {
    return _scanChannel.receiveBroadcastStream().map((event) {
      if (event is List) {
        return event.map((device) => BluetoothDevice.fromMap(Map<String, dynamic>.from(device))).toList();
      } else {
        return [];
      }
    });
  }

  static Stream<BluetoothEvent> get bluetoothEvents {
    return _bluetoothEventChannel.receiveBroadcastStream().map((event) {
      if (event is Map) {
        return BluetoothEvent.fromMap(Map<String, dynamic>.from(event));
      } else {
        throw Exception("Invalid BluetoothEvent payload");
      }
    });
  }

  static Future<void> startScan() async {
    await _channel.invokeMethod('startScan');
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