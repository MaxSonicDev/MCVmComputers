package mcvmcomputers.mixins;

import org.spongepowered.asm.mixin.injection.At;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ConcurrentModificationException;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.virtualbox_6_1.IMachine;
import org.virtualbox_6_1.IProgress;
import org.virtualbox_6_1.ISession;
import org.virtualbox_6_1.LockType;
import org.virtualbox_6_1.MachineState;
import org.virtualbox_6_1.VBoxException;

import mcvmcomputers.entities.EntityItemPreview;
import mcvmcomputers.gui.GuiSetup;
import mcvmcomputers.item.ItemList;
import mcvmcomputers.item.ItemOrderingTablet;
import mcvmcomputers.tablet.TabletOS;
import mcvmcomputers.utils.VMRunnable;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.FatalErrorScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.dimension.DimensionType;

import static mcvmcomputers.MCVmComputersMod.*;

@Mixin(MinecraftClient.class)
public class GameloopMixin {
	@Shadow
	public ClientPlayerEntity player;
	
	@Shadow
	private IntegratedServer server;
	
	@Shadow
	public HitResult crosshairTarget;
	
	@Shadow
	public ClientWorld world;
	
	@Shadow
	public Screen currentScreen;
	
	@Shadow
	private boolean paused;
	
	@Shadow
	private float pausedTickDelta;
	
	@Shadow
	private RenderTickCounter renderTickCounter;
	
	@Inject(at = @At("HEAD"), method = "run()V")
	private void run(CallbackInfo info) {
		MinecraftClient mcc = MinecraftClient.getInstance();
		mcc.openScreen(new GuiSetup());
		vhdDirectory = new File(mcc.runDirectory, "vm_computers/vhds");
		vhdDirectory.mkdirs();
		isoDirectory = new File(mcc.runDirectory, "vm_computers/isos");
		isoDirectory.mkdirs();
		
		File num = new File(vhdDirectory.getParentFile(), "vhdnum");
		if(num.exists()) {
			try {
				List<String> lines = Files.readAllLines(num.toPath());
				latestVHDNum = Integer.parseInt(lines.get(0));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	@Inject(at = @At("HEAD"), method = "render()V")
	private void render(CallbackInfo info) {
		if(lastDeltaTimeTime == 0) {
			lastDeltaTimeTime = System.currentTimeMillis();
		}else {
			long now = System.currentTimeMillis();
			long diff = now - lastDeltaTimeTime;
			lastDeltaTimeTime = now;
			
			deltaTime = (float) diff / 1000f;
		}
		
		if(this.currentScreen instanceof MultiplayerScreen) {
			MinecraftClient.getInstance().openScreen(new FatalErrorScreen(new LiteralText("Vm Computers").formatted(Formatting.RED), "You can't play multiplayer with this mod installed."));
		}
		
		if(tabletOS != null) {
			tabletOS.generateTexture();
		}else {
			try {
				tabletOS = new TabletOS();
				tabletThread = new Thread(new Runnable() {
					@Override
					public void run() {
						while(true) {
							try {tabletOS.render();}catch(ConcurrentModificationException e) {}
						}
					}
				}, "Tablet Renderer");
				tabletThread.start();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if(vboxWebSrv != null) {
			try {
				while(vboxWebSrv.getInputStream().available() > 0) {
					discardAllBytes.write(vboxWebSrv.getInputStream().read()); //Not doing this made the web service time out.
				}
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
		
		if(vmTurnedOn) {
			if(player == null && server == null) {
				vmUpdateThread.interrupt();
				
				IMachine m = vb.findMachine("VmComputersVm");
				ISession sess = vbManager.getSessionObject();
				m.lockMachine(sess, LockType.Shared);
				IProgress pg = sess.getConsole().powerDown();
				pg.waitForCompletion(-1);
				sess.unlockMachine();
				vmTurnedOn = false;
				vmTurningOff = false;
				vmTurningOn = false;
			}else {
				if(vmUpdateThread == null) {
					vmUpdateThread = new Thread(new VMRunnable(), "VM Update Thread");
					vmUpdateThread.start();
				}
				
				generatePCScreen();
			}
		}else {
			if(vmTextureNativeImage != null) {
				vmTextureNativeImage.close();
				vmTextureNIBT.close();
			}
			NativeImage ni = new NativeImage(128, 128, true);
			NativeImageBackedTexture nibt = new NativeImageBackedTexture(ni);
			vmTextureNativeImage = ni;
			if(vmTextureIdentifier != null) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(vmTextureIdentifier);
			}
			vmTextureIdentifier = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("vm_texture", nibt);
			vmTextureNIBT = nibt;
		}
		
		if(player != null) {
			if(player.getActiveItem() != null) {
				boolean tabletOut = false;
				for(ItemStack is : player.getItemsHand()) {
					if(is.getItem() != null) {
						if(is.getItem() instanceof ItemOrderingTablet) {
							tabletOut = true;
							break;
						}
					}
				}
				
				if(tabletOut != tabletOS.tabletOn) {
					tabletOS.tabletOn = tabletOut;
					
					if(tabletOut) {
						tabletOS.tabletTakenOut();
					}else {
						tabletOS.tabletUnequipped();
					}
				}
				
				for(ItemStack is : player.getItemsHand()) {
					if(is.getItem() != null) {
						if(ItemList.placableItems.contains(is.getItem())) {
							if(thePreviewEntity != null) {
								thePreviewEntity.setItem(is);
								if(crosshairTarget != null) { 
									Vec3d hit = crosshairTarget.getPos();
									thePreviewEntity.updatePosition(hit.x, hit.y, hit.z);
									if(world.getEntityById(thePreviewEntity.getEntityId()) != null) {
										world.getEntityById(thePreviewEntity.getEntityId()).updatePosition(hit.x, hit.y, hit.z);
									}
								}else {
									break;
								}
							}else {
								if(crosshairTarget != null) {
									Vec3d hit = crosshairTarget.getPos();
									thePreviewEntity = new EntityItemPreview(server.getWorld(DimensionType.OVERWORLD), hit.x, hit.y, hit.z, is);
									this.server.getWorld(DimensionType.OVERWORLD).spawnEntity(thePreviewEntity);
								}
							}
						}else {
							break;
						}
					}else {
						break;
					}
					return;
				}
				if(thePreviewEntity != null) {
					thePreviewEntity.kill();
					thePreviewEntity = null;
				}
			}
		}
	}
	
	@Inject(at = @At("HEAD"), method = "close()V")
	private void close(CallbackInfo info) {
		System.out.println("Stopping VM Computers Mod...");
		if(vmTextureNativeImage != null) {
			vmTextureNativeImage.close();
		}
		if(vmTextureNIBT != null) {
			vmTextureNIBT.close();
		}
		if(vmUpdateThread != null) {
			vmUpdateThread.interrupt();
		}
		
		if(tabletThread != null) {
			tabletThread.interrupt();
		}
		
		vmTurningOn = false;
		vmTurnedOn = false;
		vmTurningOff = false;
		
		if(vbManager != null) {
			boolean vmExists = false;
			IMachine mach = null;
			try {
				mach = vb.findMachine("VmComputersVm");
				vmExists = true;
			}catch(VBoxException e) {}
			
			if(vmExists) {
				if(mach.getState() == MachineState.Running || mach.getState() == MachineState.Starting) {
					if(vmSession != null) {
						IProgress ip = vmSession.getConsole().powerDown();
						ip.waitForCompletion(-1);
						vmSession.unlockMachine();
					}else {
						ISession sess = vbManager.getSessionObject();
						mach.lockMachine(sess, LockType.Shared);
						IProgress ip = sess.getConsole().powerDown();
						ip.waitForCompletion(-1);
						sess.unlockMachine();
					}
				}
			}
			vbManager.cleanup();
		}
		if(vboxWebSrv != null) {
			vboxWebSrv.destroy();
		}
		System.out.println("Stopped VM Computers Mod.");
	}
}