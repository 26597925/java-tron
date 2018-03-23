package org.tron.common.overlay.message;

import org.spongycastle.util.encoders.Hex;
import org.tron.core.net.message.MessageTypes;

public class PingMessage extends P2pMessage {

  private static final byte[] FIXED_PAYLOAD = Hex.decode("C0");

  public PingMessage() {
    this.type = MessageTypes.P2P_PING.asByte();
    this.data = FIXED_PAYLOAD;
  }

  public PingMessage(byte type, byte[] rawData) {
    super(type, rawData);
  }

  @Override
  public byte[] getData() {
    return FIXED_PAYLOAD;
  }

  @Override
  public P2pMessageCodes getCommand() {
    return P2pMessageCodes.PING;
  }

  @Override
  public String toString() {
    return "[" + getCommand().name() + "]";
  }

  @Override
  public Class<?> getAnswerMessage() {
    return null;
  }

  @Override
  public MessageTypes getType() {
    return MessageTypes.fromByte(this.type);
  }
}