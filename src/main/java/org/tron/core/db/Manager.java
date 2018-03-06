package org.tron.core.db;

import com.carrotsearch.sizeof.RamUsageEstimator;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.actuator.Actuator;
import org.tron.core.actuator.ActuatorFactory;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.capsule.utils.BlockUtil;
import org.tron.core.config.args.Args;
import org.tron.protos.Protocal.Account;
import org.tron.protos.Protocal.Transaction;
import org.tron.protos.Protocal.Witness;

public class Manager {

  private static final Logger logger = LoggerFactory.getLogger("Manager");

  private static final long BLOCK_INTERVAL_SEC = 1;
  private static final int MAX_ACTIVE_WITNESS_NUM = 21;
  private static final long TRXS_SIZE = 2_000_000; // < 2MiB

  private AccountStore accountStore;
  private TransactionStore transactionStore;
  private BlockStore blockStore;
  private UtxoStore utxoStore;
  private WitnessStore witnessStore;
  private DynamicPropertiesStore dynamicPropertiesStore;

  public WitnessStore getWitnessStore() {
    return witnessStore;
  }

  private void setWitnessStore(WitnessStore witnessStore) {
    this.witnessStore = witnessStore;
  }

  public DynamicPropertiesStore getDynamicPropertiesStore() {
    return dynamicPropertiesStore;
  }

  public void setDynamicPropertiesStore(DynamicPropertiesStore dynamicPropertiesStore) {
    this.dynamicPropertiesStore = dynamicPropertiesStore;
  }

  public List<Transaction> getPendingTrxs() {
    return pendingTrxs;
  }


  // transaction cache
  private List<Transaction> pendingTrxs;

  private List<WitnessCapsule> wits = new ArrayList<>();

  // witness

  public List<WitnessCapsule> getWitnesses() {
    return wits;
  }

  public Sha256Hash getHeadBlockId() {
    return Sha256Hash.wrap(this.dynamicPropertiesStore.getLatestBlockHeaderHash());
  }

  /**
   * TODO: should get this list from Database. get witnessCapsule List.
   */
  public void initalWitnessList() {
    wits.add(new WitnessCapsule(ByteString.copyFromUtf8("0x01"),"http://Loser.org"));
    wits.add(new WitnessCapsule(ByteString.copyFromUtf8("0x02"),"http://Marcus.org"));
    wits.add(new WitnessCapsule(ByteString.copyFromUtf8("0x02"),"http://Olivier.org"));
  }

  public void addWitness(WitnessCapsule witnessCapsule) {
    this.wits.add(witnessCapsule);
  }

  public List<WitnessCapsule> getCurrentShuffledWitnesses() {
    return getWitnesses();
  }


  /**
   * get ScheduledWitness by slot.
   */
  public ByteString getScheduledWitness(long slot) {

    if (blockStore == null) {
      logger.error("blockStore is null");
      return null;
    }

    long currentSlot = blockStore.currentASlot() + slot;

    if (currentSlot < 0) {
      throw new RuntimeException("currentSlot should be positive.");
    }
    List<WitnessCapsule> currentShuffledWitnesses = getShuffledWitnesses();
    if (CollectionUtils.isEmpty(currentShuffledWitnesses)) {
      throw new RuntimeException("ShuffledWitnesses is null.");
    }
    int witnessIndex = (int) currentSlot % currentShuffledWitnesses.size();

    ByteString scheduledWitness = currentShuffledWitnesses.get(witnessIndex).getAddress();
    //logger.info("scheduled_witness:" + scheduledWitness.toStringUtf8() + ",slot:" + currentSlot);

    return scheduledWitness;
  }

  public int calculateParticipationRate() {
    return 100 * dynamicPropertiesStore.calculateFilledSlotsCount() / 128;
  }

  public List<WitnessCapsule> getShuffledWitnesses() {
    List<WitnessCapsule> shuffleWits = getWitnesses();
    //Collections.shuffle(shuffleWits);
    return shuffleWits;
  }


  /**
   * all db should be init here.
   */
  public void init() {
    setAccountStore(AccountStore.create("account"));
    setTransactionStore(TransactionStore.create("trans"));
    setBlockStore(BlockStore.create("block"));
    setUtxoStore(UtxoStore.create("utxo"));
    setWitnessStore(WitnessStore.create("witness"));
    setDynamicPropertiesStore(DynamicPropertiesStore.create("properties"));

    pendingTrxs = new ArrayList<>();
    initGenesis();
    blockStore.initHeadBlock(Sha256Hash.wrap(this.dynamicPropertiesStore.getLatestBlockHeaderHash()));
  }

  /**
   * init genesis block.
   */
  public void initGenesis() {
    BlockCapsule genesisBlockCapsule = BlockUtil.newGenesisBlockCapsule();
    if (this.getBlockStore().containBlock(genesisBlockCapsule.getBlockId())) {
      Args.getInstance().setChainId(genesisBlockCapsule.getBlockId().toString());
    } else {
      if (this.getBlockStore().hasBlocks()) {
        logger.error("genesis block modify, please delete database directory({}) and restart",
            Args.getInstance().getOutputDirectory());
        System.exit(1);
      } else {
        logger.info("create genesis block");
        Args.getInstance().setChainId(genesisBlockCapsule.getBlockId().toString());
        this.getBlockStore().pushBlock(genesisBlockCapsule);
        this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(0);
        this.dynamicPropertiesStore.saveLatestBlockHeaderHash(genesisBlockCapsule.getBlockId().getByteString());
        this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(genesisBlockCapsule.getTimeStamp());
      }
    }
  }

  public AccountStore getAccountStore() {
    return accountStore;
  }

  public void pushTrx(Transaction trx) {
    this.pendingTrxs.add(trx);
  }

  /**
   * Process transaction.
   */
  public boolean processTrx(TransactionCapsule trxCap) {

    if (trxCap == null || !trxCap.validateSignature()) {
      return false;
    }

    //ActuatorFactory actuatorFactory = ActuatorFactory.getInstance();
    List<Actuator> actuatorList = ActuatorFactory.createActuator(trxCap, this);
    assert actuatorList != null;
    actuatorList.forEach(actuator -> actuator.execute());
    return true;
  }


  /**
   * Generate a block.
   */
  public BlockCapsule generateBlock(WitnessCapsule witnessCapsule,
      long when, byte[] privateKey) {

    final long timestamp = this.dynamicPropertiesStore.getLatestBlockHeaderTimestamp();

    final long number = this.dynamicPropertiesStore.getLatestBlockHeaderNumber();

    final ByteString preHash = this.dynamicPropertiesStore.getLatestBlockHeaderHash();

    // judge create block time
    if (when < timestamp) {
      throw new IllegalArgumentException("generate block timestamp is invalid.");
    }

    long currentTrxSize = 0;
    long postponedTrxCount = 0;

    BlockCapsule blockCapsule = new BlockCapsule(number + 1, preHash, when,
        witnessCapsule.getAddress());

    for (Transaction trx : pendingTrxs) {
      currentTrxSize += RamUsageEstimator.sizeOf(trx);
      // judge block size
      if (currentTrxSize > TRXS_SIZE) {
        postponedTrxCount++;
        continue;
      }

      // apply transaction
      if (processTrx(new TransactionCapsule(trx))) {
        // push into block
        blockCapsule.addTransaction(trx);
        pendingTrxs.remove(trx);
      }
    }

    if (postponedTrxCount > 0) {
      logger.info("{} transactions over the block size limit", postponedTrxCount);
    }

    blockCapsule.setMerklerRoot();
    blockCapsule.sign(privateKey);
    blockCapsule.generatedByMyself = true;
    getBlockStore().pushBlock(blockCapsule);

    dynamicPropertiesStore.saveLatestBlockHeaderHash(blockCapsule.getBlockId().getByteString());
    dynamicPropertiesStore.saveLatestBlockHeaderNumber(blockCapsule.getNum());
    dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(blockCapsule.getTimeStamp());
    return blockCapsule;
  }

  private void setAccountStore(AccountStore accountStore) {
    this.accountStore = accountStore;
  }

  public TransactionStore getTransactionStore() {
    return transactionStore;
  }

  private void setTransactionStore(TransactionStore transactionStore) {
    this.transactionStore = transactionStore;
  }

  public BlockStore getBlockStore() {
    return blockStore;
  }

  private void setBlockStore(BlockStore blockStore) {
    this.blockStore = blockStore;
  }

  public UtxoStore getUtxoStore() {
    return utxoStore;
  }

  private void setUtxoStore(UtxoStore utxoStore) {
    this.utxoStore = utxoStore;
  }

  public void processBlock(BlockCapsule block) {
    block.getTransactions().forEach(transactionCapsule -> {
      processTrx(transactionCapsule);
    });
  }

  public void updateWitness() {
    //TODO validate maint needed
    Map<ByteString, Long> countWitness = Maps.newHashMap();
    List<Account> accountList = accountStore.getAllAccounts();
    accountList.forEach(account -> {
      account.getVotesList().forEach(vote -> {
        //TODO validate witness //active_witness
        if (countWitness.containsKey(vote.getVoteAddress())) {
          countWitness.put(vote.getVoteAddress(),
              countWitness.get(vote.getVoteAddress()) + vote.getVoteCount());
        } else {
          countWitness.put(vote.getVoteAddress(), vote.getVoteCount());
        }
      });
    });
    List<WitnessCapsule> witnessCapsuleList = Lists.newArrayList();
    countWitness.forEach((address, voteCount) -> {
      Witness witnessSource = witnessStore.getWitness(address);
      if (null == witnessSource) {
        logger.warn("winessSouece is null.address is {}", address);
      }
      Witness witnessTarget = witnessSource.toBuilder().setVoteCount(voteCount).build();
      witnessCapsuleList.add(new WitnessCapsule(witnessTarget));
      witnessStore.putWitness(witnessTarget);
    });
    witnessCapsuleList.sort((a, b) -> {
      return (int) (a.getVoteCount() - b.getVoteCount());
    });
    wits = witnessCapsuleList.subList(0, MAX_ACTIVE_WITNESS_NUM);
  }
}
