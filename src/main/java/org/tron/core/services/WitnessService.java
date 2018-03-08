package org.tron.core.services;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.common.application.Application;
import org.tron.common.application.Service;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.RandomGenerator;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.db.BlockStore;
import org.tron.core.db.Manager;
import org.tron.core.exception.CancelException;
import org.tron.core.exception.ValidateSignatureException;
import org.tron.core.net.message.BlockMessage;
import org.tron.core.witness.BlockProductionCondition;


public class WitnessService implements Service {

  private static final Logger logger = LoggerFactory.getLogger(WitnessService.class);
  private static final int MIN_PARTICIPATION_RATE = 33; // MIN_PARTICIPATION_RATE * 1%
  private static final int PRODUCE_TIME_OUT = 500; // ms
  private Application tronApp;
  @Getter
  protected WitnessCapsule localWitnessState; //  WitnessId;
  @Getter
  protected List<WitnessCapsule> witnessStates;
  private Thread generateThread;
  private Manager db;
  private volatile boolean isRunning = false;
  private static final int LOOP_INTERVAL = 1000; // millisecond
  private byte[] privateKey;
  private boolean hasCheckedSynchronization = true;
  private volatile boolean canceled = false;

  /**
   * Construction method.
   */
  public WitnessService(Application tronApp) {
    this.tronApp = tronApp;
    db = tronApp.getDbManager();
    generateThread = new Thread(scheduleProductionLoop);
    init();
  }

  private Runnable scheduleProductionLoop =
      () -> {
        if (localWitnessState == null) {
          logger.error("local witness is null");
        }

        while (isRunning) {
          DateTime time = DateTime.now();
          int timeToNextSecond = LOOP_INTERVAL - time.getMillisOfSecond();
          if (timeToNextSecond < 50) {
            timeToNextSecond = timeToNextSecond + LOOP_INTERVAL;
          }
          try {
            DateTime nextTime = time.plus(timeToNextSecond);
            logger.info("sleep : " + timeToNextSecond + " ms,next time:" + nextTime);
            Thread.sleep(timeToNextSecond);
            blockProductionLoop();

            updateWitnessSchedule();
          } catch (Exception ex) {
            logger.error("ProductionLoop error", ex);
          }
        }
      };

  private void blockProductionLoop() throws CancelException {
    BlockProductionCondition result;
    try {
      result = tryProduceBlock();
    } catch (CancelException ex) {
      throw ex;
    } catch (Exception ex) {
      logger.error("produce block error,", ex);
      result = BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
    }

    if (result == null) {
      logger.warn("result is null");
      return;
    }

    switch (result) {
      case PRODUCED:
        logger.info("Porduced");
        break;
      case NOT_SYNCED:
        logger.info("not sync");
        break;
      case NOT_MY_TURN:
        logger.info("It's not my turn");
        break;
      case NOT_TIME_YET:
        logger.info("not time yet");
        break;
      case NO_PRIVATE_KEY:
        logger.info("no pri key");
        break;
      case LOW_PARTICIPATION:
        logger.info("low part");
        break;
      case LAG:
        logger.info("lag");
        break;
      case CONSECUTIVE:
        logger.info("consecutive");
        break;
      case EXCEPTION_PRODUCING_BLOCK:
        logger.info("excpetion");
        break;
      default:
        break;
    }
  }


  private BlockProductionCondition tryProduceBlock()
      throws ValidateSignatureException, CancelException {

    checkCancelFlag();

    if (!hasCheckedSynchronization) {
      return BlockProductionCondition.NOT_SYNCED;
    }

    int participation = db.calculateParticipationRate();
    if (participation < MIN_PARTICIPATION_RATE) {
      logger.warn(
          "Participation[" + participation + "] <  MIN_PARTICIPATION_RATE[" + MIN_PARTICIPATION_RATE
              + "]");
      return BlockProductionCondition.LOW_PARTICIPATION;
    }

    long slot = getSlotAtTime(DateTime.now());
    logger.debug("slot:" + slot);

    if (slot == 0) {
      // todo capture error message
      return BlockProductionCondition.NOT_TIME_YET;
    }

    ByteString scheduledWitness = db.getScheduledWitness(slot);

    if (!scheduledWitness.equals(getLocalWitnessState().getAddress())) {
      return BlockProductionCondition.NOT_MY_TURN;
    }

    DateTime scheduledTime = getSlotTime(slot);

    //TODO:implement private and public key code, fake code first.

    if (scheduledTime.getMillis() - DateTime.now().getMillis() > PRODUCE_TIME_OUT) {
      return BlockProductionCondition.LAG;
    }

    //TODO:implement private and public key code, fake code first.
    BlockCapsule block = null;
    try {
      block = generateBlock(scheduledTime);
    } catch (ValidateSignatureException e) {
      e.printStackTrace();
    }
    logger.info("Block is generated successfully, Its Id is " + block.getBlockId());

    broadcastBlock(block);
    return BlockProductionCondition.PRODUCED;
  }

  private void checkCancelFlag() throws CancelException {
    if (canceled) {
      throw new CancelException();
    }
  }

  private void broadcastBlock(BlockCapsule block) {
    try {
      tronApp.getP2pNode().broadcast(new BlockMessage(block.getData()));
    } catch (Exception ex) {
      throw new RuntimeException("broadcastBlock error");
    }
  }

  private BlockCapsule generateBlock(DateTime when) throws ValidateSignatureException {
    return tronApp.getDbManager().generateBlock(localWitnessState, when.getMillis(), privateKey);
  }

  private DateTime getSlotTime(long slotNum) {
    if (slotNum == 0) {
      return DateTime.now();
    }
    long interval = blockInterval();
    BlockStore blockStore = tronApp.getDbManager().getBlockStore();
    DateTime genesisTime = blockStore.getGenesisTime();
    if (blockStore.getHeadBlockNum() == 0) {
      return genesisTime.plus(slotNum * interval);
    }

    if (lastHeadBlockIsMaintenance()) {
      slotNum += getSkipSlotInMaintenance();
    }

    DateTime headSlotTime = blockStore.getHeadBlockTime();

    //align slot time
    headSlotTime = headSlotTime
        .minus((headSlotTime.getMillis() - genesisTime.getMillis()) % interval);

    return headSlotTime.plus(interval * slotNum);
  }

  private boolean lastHeadBlockIsMaintenance() {
    return db.getDynamicPropertiesStore().getStateFlag() == 1;
  }

  private long getSkipSlotInMaintenance() {
    return 0;
  }

  private long getSlotAtTime(DateTime when) {
    DateTime firstSlotTime = getSlotTime(1);
    if (when.isBefore(firstSlotTime)) {
      return 0;
    }
    return (when.getMillis() - firstSlotTime.getMillis()) / blockInterval() + 1;
  }


  private long blockInterval() {
    return LOOP_INTERVAL; // millisecond todo getFromDb
  }


  // shuffle witnesses
  private void updateWitnessSchedule() {
    if (db.getBlockStore().getHeadBlockNum() % witnessStates.size() == 0) {
      String witnessStringListBefore = getWitnessStringList(witnessStates).toString();
      witnessStates = new RandomGenerator<WitnessCapsule>()
          .shuffle(witnessStates, db.getBlockStore().getHeadBlockTime());
      logger.info("updateWitnessSchedule,before: " + witnessStringListBefore + ",after: "
          + getWitnessStringList(witnessStates));
    }
  }

  private List<String> getWitnessStringList(List<WitnessCapsule> witnessStates) {
    return witnessStates.stream()
        .map(witnessCapsule -> ByteArray.toHexString(witnessCapsule.getAddress().toByteArray()))
        .collect(Collectors.toList());
  }

  // shuffle todo
  @Override
  public void init() {
    this.privateKey = Args.getInstance().getInitialWitness().getLocalWitness().getPrivateKey()
        .getBytes();
    tronApp.getDbManager().initialWitnessList();
    localWitnessState = new WitnessCapsule(
        ByteString.copyFrom(ECKey.fromPrivate(this.privateKey).getPubKey()),
        Args.getInstance().getInitialWitness().getLocalWitness().getUrl());
//    tronApp.getDbManager().addWitness(localWitnessState);
    this.witnessStates = db.getWitnesses();
  }


  @Override
  public void init(Args args) {
    //this.privateKey = args.getPrivateKey();
    init();
  }

  @Override
  public void start() {
    isRunning = true;
    generateThread.start();
  }

  @Override
  public void stop() {
    isRunning = false;
    generateThread.interrupt();
  }
}
