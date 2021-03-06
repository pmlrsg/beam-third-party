package uk.ac.ucl.mssl.climatephysics.beam.stereomatcher;

import org.esa.beam.framework.gpf.ui.DefaultSingleTargetProductDialog;
import org.esa.beam.framework.ui.command.CommandEvent;

public class M4StereoMatcherAction extends M5StereoMatcherAction {
    private DefaultSingleTargetProductDialog dialog;

    @Override
    public void actionPerformed(CommandEvent event) {
        if (null == dialog) {
            dialog = new DefaultSingleTargetProductDialog("M4StereoMatcher",
                                                          getAppContext(), 
                                                          "MSSL M4 Stereo Matcher",
                                                          "stereoMatcher");
            dialog.setTargetProductNameSuffix("_M4StereoMatcher");
        }
        dialog.show();
    }

}
