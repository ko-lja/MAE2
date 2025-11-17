package stone.mae2.integration.ae2cl;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.capabilities.Capabilities;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternProviderTargetCache;
import appeng.items.parts.PartModels;
import appeng.me.helpers.MachineSource;
import appeng.parts.p2p.P2PModels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import stone.mae2.MAE2;
import stone.mae2.parts.p2p.multi.MultiP2PTunnel;
import stone.mae2.util.TransHelper;

import java.util.ArrayList;
import java.util.List;

public class PatternMultiP2PTunnelCL extends
        MultiP2PTunnel<PatternMultiP2PTunnelCL, PatternMultiP2PTunnelCL.Logic, PatternMultiP2PTunnelCL.PartCL>
  implements PatternP2PTunnelLogicCL.PatternP2PTunnel {

  protected List<PartCL> inputs;
  protected List<PartCL> outputs;
  protected PatternP2PTunnelLogicCL logic;

  public PatternMultiP2PTunnelCL(short freq, IGrid grid) {
    super(freq, grid);
    this.inputs = new ArrayList<>();
    this.outputs = new ArrayList<>();
    this.logic = new PatternP2PTunnelLogicCL(this);
  }

  @Override
  public Logic addTunnel(PartCL part) {
    Logic logic = super.addTunnel(part);
    if (part.isOutput()) {
      this.outputs.add(part);
      this.logic.refreshOutputs();
    } else {
      this.inputs.add(part);
      this.logic.refreshInputs();
    }
    return logic;
  }

  @Override
  public boolean removeTunnel(PartCL part) {
    if (part.isOutput()) {
      this.outputs.remove(part);
      this.logic.refreshOutputs();
    } else {
      this.inputs.remove(part);
      this.logic.refreshInputs();
    }
    return super.removeTunnel(part);
  }

  private boolean isRecursive = false;
  private boolean wasRecursive = false;

  @Override
  public PatternContainerGroup getGroup() {
    if (isRecursive) {
      wasRecursive = true;
      return new PatternContainerGroup(AEItemKey.of(Items.BARRIER),
        TransHelper.GUI.translatable("patternP2P.recursive"), List.of());
    }
    if (wasRecursive) {
      wasRecursive = false;
      return new PatternContainerGroup(AEItemKey.of(Items.BARRIER),
        TransHelper.GUI.translatable("patternP2P.recursive"), List.of());
    }
    try {
      isRecursive = true;
      PatternContainerGroup group = PatternP2PTunnelLogicCL.PatternP2PTunnel.super.getGroup();
      if (wasRecursive) {
        wasRecursive = false;
        return new PatternContainerGroup(AEItemKey.of(Items.BARRIER),
          TransHelper.GUI.translatable("patternP2P.recursive"), List.of());
      }
      // always called on the input part anyways, no need to get it
      if (this.hasCustomName())
        return new PatternContainerGroup(group.icon(),
          TransHelper.GUI
            .translatable("patternP2P.aggregate", this.getCustomName(),
              this.getOutputs().size()),
          group.tooltip());
      else
        return group;
    } finally {
      isRecursive = false;
    }
  }

  @Override
  public List<? extends PatternP2PTunnelLogicCL.Target> getPatternTunnelInputs() { return inputs; }

  @Override
  public List<? extends PatternP2PTunnelLogicCL.Target> getPatternTunnelOutputs() { return outputs; }

  @Override
  public Logic createLogic(PartCL part) {
    return part.setLogic(new Logic(part));
  }

  public class Logic extends
    MultiP2PTunnel<PatternMultiP2PTunnelCL, PatternMultiP2PTunnelCL.Logic, PartCL>.Logic {

    public Logic(PartCL part) {
      super(part);
    }

    public <T> LazyOptional<T> getCapability(Capability<T> capabilityClass) {
      if (capabilityClass == Capabilities.CRAFTING_MACHINE)
        return (LazyOptional<T>) LazyOptional
          .of(() -> PatternMultiP2PTunnelCL.this.logic);
      if (this.part.isOutput()) {
        var inputList = PatternMultiP2PTunnelCL.this
          .getPatternTunnelInputs();
        if (inputList.isEmpty())
          return LazyOptional.empty();
        var provider = inputList.get(0);
        if (provider == null)
          return LazyOptional.empty();
        BlockEntity maybeEntity = provider.getTargetBlockEntity();
        if (maybeEntity != null) {
          if (maybeEntity instanceof ICraftingProvider
            || maybeEntity instanceof PatternProviderLogicHost) {
            return maybeEntity.getCapability(capabilityClass, provider.side());
          } else if (maybeEntity instanceof IPartHost host) {
            IPart maybePart = host.getPart(((PartCL) provider).getSide());
            if (maybePart != null && (maybePart instanceof ICraftingProvider
              || maybePart instanceof PatternProviderLogicHost)) {
              maybePart.getCapability(capabilityClass);
            }
          }
        }
      }
      return LazyOptional.empty();
    }
  }

  public static class PartCL extends
    MultiP2PTunnel.Part<PatternMultiP2PTunnelCL, PatternMultiP2PTunnelCL.Logic, PartCL>
    implements PatternP2PPartLogicCL.PatternP2PPartLogicHostCL {
    private static final P2PModels MODELS = new P2PModels(
      MAE2.toKey("part/p2p/multi_p2p_tunnel_pattern"));
    protected final IActionSource source;

    private final PatternP2PPartLogicCL partLogic = new PatternP2PPartLogicCL(this);
    private PatternProviderTargetCache cache;

    public PartCL(IPartItem<?> partItem) {
      super(partItem);
      this.source = new MachineSource(this);
      if (this.getBlockEntity() != null) {
        this.cache = PatternP2PPartLogicCL.PatternP2PPartLogicHostCL.super.getCache();
      } else
        this.cache = null;
    }

    @Override
    public void readFromNBT(CompoundTag data) {
      super.readFromNBT(data);
      this.partLogic.readFromNBT(data);
    }

    @Override
    public void writeToNBT(CompoundTag data) {
      super.writeToNBT(data);
      this.partLogic.writeToNBT(data);
    }

    @Override
    public void addToWorld() {
      super.addToWorld();
      this.cache = PatternP2PPartLogicCL.PatternP2PPartLogicHostCL.super.getCache();
    }

    @PartModels
    public static List<IPartModel> getModels() { return MODELS.getModels(); }

    public IPartModel getStaticModels() {
      return MODELS.getModel(this.isPowered(), this.isActive());
    }

    @Override
    public PatternMultiP2PTunnelCL createTunnel(short freq) {
      return new PatternMultiP2PTunnelCL(freq, this.getGridNode().getGrid());
    }

    @Override
    public Class<PatternMultiP2PTunnelCL> getTunnelClass() {
      return PatternMultiP2PTunnelCL.class;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capabilityClass) {
      if (this.logic != null && this.getFrequency() != 0)
        return this.logic.getCapability(capabilityClass);
      return LazyOptional.empty();
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
      this.partLogic.addAdditionalDrops(drops, wrenched);
    }

    @Override
    public ServerLevel level() {
      return (ServerLevel) this.getBlockEntity().getLevel();
    }

    @Override
    public boolean isValid() { // TODO Auto-generated method stub
      return this.partLogic.isValid();
    }

    @Override
    public void addToSendList(AEKey what, long l) {
      this.partLogic.addToSendList(what, l);
    }

    @Override
    public BlockPos pos() {
      return this.getBlockEntity().getBlockPos().relative(this.getSide());
    }

    @Override
    public Direction side() {
      return this.getSide().getOpposite();
    }

    @Override
    public IActionSource source() {
      return source;
    }

    @Override
    public @NotNull PatternProviderTargetCache getCache() { return this.cache; }
  }

}
