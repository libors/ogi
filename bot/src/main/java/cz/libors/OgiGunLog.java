package cz.libors;

/**
 * Ogi that also logs its gun's inputs and decisions for the offline gun bench (see {@link Ogi#logsGun()}). It plays
 * exactly like Ogi. Its jar is deployed only locally and to the harness's workers, never to a rumble.
 */
public class OgiGunLog extends Ogi {

    @Override
    protected boolean logsGun() {
        return true;
    }
}
