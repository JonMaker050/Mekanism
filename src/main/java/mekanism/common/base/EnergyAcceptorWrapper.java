package mekanism.common.base;

import cofh.redstoneflux.api.IEnergyReceiver;
import ic2.api.energy.EnergyNet;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergyTile;
import java.util.Objects;
import mekanism.api.Coord4D;
import mekanism.api.energy.IStrictEnergyAcceptor;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.config.MekanismConfig;
import mekanism.common.util.CapabilityUtils;
import mekanism.common.util.MekanismUtils;
import net.darkhax.tesla.api.ITeslaConsumer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class EnergyAcceptorWrapper implements IStrictEnergyAcceptor {

    private static final Logger LOGGER = LogManager.getLogger("Mekanism EnergyAcceptorWrapper");
    public Coord4D coord;

    public static EnergyAcceptorWrapper get(TileEntity tileEntity, EnumFacing side) {
        if (tileEntity == null || tileEntity.getWorld() == null) {
            return null;
        }

        EnergyAcceptorWrapper wrapper = null;

        if (CapabilityUtils.hasCapability(tileEntity, Capabilities.ENERGY_ACCEPTOR_CAPABILITY, side)) {
            IStrictEnergyAcceptor mekAcceptor = CapabilityUtils
                  .getCapability(tileEntity, Capabilities.ENERGY_ACCEPTOR_CAPABILITY, side);
            if (mekAcceptor != null) {
                wrapper = new MekanismAcceptor(mekAcceptor);
            } else {
                LOGGER.error("Tile {} @ {} told us it had IStrictEnergyAcceptor cap but returned null", tileEntity,
                      tileEntity.getPos());
            }
        } else if (MekanismUtils.useTesla() && CapabilityUtils
              .hasCapability(tileEntity, Capabilities.TESLA_CONSUMER_CAPABILITY, side)) {
            ITeslaConsumer teslaConsumer = CapabilityUtils
                  .getCapability(tileEntity, Capabilities.TESLA_CONSUMER_CAPABILITY, side);
            if (teslaConsumer != null) {
                wrapper = new TeslaAcceptor(teslaConsumer);
            } else {
                LOGGER.error("Tile {} @ {} told us it had ITeslaConsumer cap but returned null", tileEntity,
                      tileEntity.getPos());
            }
        } else if (MekanismUtils.useForge() && CapabilityUtils
              .hasCapability(tileEntity, CapabilityEnergy.ENERGY, side)) {
            IEnergyStorage forgeConsumer = CapabilityUtils.getCapability(tileEntity, CapabilityEnergy.ENERGY, side);
            if (forgeConsumer != null) {
                wrapper = new ForgeAcceptor(forgeConsumer);
            } else {
                LOGGER.error("Tile {} @ {} told us it had IEnergyStorage cap but returned null", tileEntity,
                      tileEntity.getPos());
            }
        } else if (MekanismUtils.useRF() && tileEntity instanceof IEnergyReceiver) {
            wrapper = new RFAcceptor((IEnergyReceiver) tileEntity);
        } else if (MekanismUtils.useIC2()) {
            IEnergyTile tile = EnergyNet.instance.getSubTile(tileEntity.getWorld(), tileEntity.getPos());

            if (tile instanceof IEnergySink) {
                wrapper = new IC2Acceptor((IEnergySink) tile);
            }
        }

        if (wrapper != null) {
            wrapper.coord = Coord4D.get(tileEntity);
        }

        return wrapper;
    }

    public abstract boolean needsEnergy(EnumFacing side);

    public static class MekanismAcceptor extends EnergyAcceptorWrapper {

        private IStrictEnergyAcceptor acceptor;

        public MekanismAcceptor(IStrictEnergyAcceptor mekAcceptor) {
            Objects.requireNonNull(mekAcceptor);
            acceptor = mekAcceptor;
        }

        @Override
        public double acceptEnergy(EnumFacing side, double amount, boolean simulate) {
            return acceptor.acceptEnergy(side, amount, simulate);
        }

        @Override
        public boolean canReceiveEnergy(EnumFacing side) {
            return acceptor.canReceiveEnergy(side);
        }

        @Override
        public boolean needsEnergy(EnumFacing side) {
            return acceptor.acceptEnergy(side, 1, true) > 0;
        }
    }

    public static class RFAcceptor extends EnergyAcceptorWrapper {

        private IEnergyReceiver acceptor;

        public RFAcceptor(IEnergyReceiver rfAcceptor) {
            Objects.requireNonNull(rfAcceptor);
            acceptor = rfAcceptor;
        }

        @Override
        public double acceptEnergy(EnumFacing side, double amount, boolean simulate) {
            return fromRF(acceptor.receiveEnergy(side, Math.min(Integer.MAX_VALUE, toRF(amount)), simulate));
        }

        @Override
        public boolean canReceiveEnergy(EnumFacing side) {
            return acceptor.canConnectEnergy(side);
        }

        @Override
        public boolean needsEnergy(EnumFacing side) {
            return acceptor.receiveEnergy(side, 1, true) > 0;
        }

        public int toRF(double joules) {
            return (int) Math.round(joules * MekanismConfig.current().general.TO_RF.val());
        }

        public double fromRF(int rf) {
            return rf * MekanismConfig.current().general.FROM_RF.val();
        }
    }

    public static class IC2Acceptor extends EnergyAcceptorWrapper {

        private IEnergySink acceptor;

        public IC2Acceptor(IEnergySink ic2Acceptor) {
            Objects.requireNonNull(ic2Acceptor);
            acceptor = ic2Acceptor;
        }

        @Override
        public double acceptEnergy(EnumFacing side, double amount, boolean simulate) {
            double toTransfer = Math.min(Math.min(acceptor.getDemandedEnergy(), toEU(amount)), Integer.MAX_VALUE);
            double rejects = acceptor.injectEnergy(side, toTransfer, 0);

            return fromEU(toTransfer - rejects);
        }

        @Override
        public boolean canReceiveEnergy(EnumFacing side) {
            return acceptor.acceptsEnergyFrom(null, side);
        }

        @Override
        public boolean needsEnergy(EnumFacing side) {
            return acceptor.getDemandedEnergy() > 0;
        }

        public double toEU(double joules) {
            return joules * MekanismConfig.current().general.TO_IC2.val();
        }

        public double fromEU(double eu) {
            return eu * MekanismConfig.current().general.FROM_IC2.val();
        }
    }

    public static class TeslaAcceptor extends EnergyAcceptorWrapper {

        private ITeslaConsumer acceptor;

        public TeslaAcceptor(ITeslaConsumer teslaConsumer) {
            Objects.requireNonNull(teslaConsumer);
            acceptor = teslaConsumer;
        }

        @Override
        public double acceptEnergy(EnumFacing side, double amount, boolean simulate) {
            return fromTesla(acceptor.givePower(toTesla(amount), false));
        }

        @Override
        public boolean canReceiveEnergy(EnumFacing side) {
            return acceptor.givePower(1, true) > 0;
        }

        @Override
        public boolean needsEnergy(EnumFacing side) {
            return canReceiveEnergy(side);
        }

        public long toTesla(double joules) {
            return Math.round(joules * MekanismConfig.current().general.TO_TESLA.val());
        }

        public double fromTesla(double tesla) {
            return tesla * MekanismConfig.current().general.FROM_TESLA.val();
        }
    }

    public static class ForgeAcceptor extends EnergyAcceptorWrapper {

        private IEnergyStorage acceptor;

        public ForgeAcceptor(IEnergyStorage forgeConsumer) {
            Objects.requireNonNull(forgeConsumer);
            acceptor = forgeConsumer;
        }

        @Override
        public double acceptEnergy(EnumFacing side, double amount, boolean simulate) {
            return fromForge(acceptor.receiveEnergy(Math.min(Integer.MAX_VALUE, toForge(amount)), simulate));
        }

        @Override
        public boolean canReceiveEnergy(EnumFacing side) {
            return acceptor.canReceive();
        }

        @Override
        public boolean needsEnergy(EnumFacing side) {
            return acceptor.canReceive();
        }

        public int toForge(double joules) {
            return (int) Math.round(joules * MekanismConfig.current().general.TO_FORGE.val());
        }

        public double fromForge(double forge) {
            return forge * MekanismConfig.current().general.FROM_FORGE.val();
        }
    }
}
