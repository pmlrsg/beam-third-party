package wew.water.gpf;

import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Mask;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.ProductNodeGroup;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.pointop.PixelOperator;
import org.esa.beam.framework.gpf.pointop.ProductConfigurer;
import org.esa.beam.framework.gpf.pointop.Sample;
import org.esa.beam.framework.gpf.pointop.SampleConfigurer;
import org.esa.beam.framework.gpf.pointop.WritableSample;
import org.esa.beam.jai.ResolutionLevel;
import org.esa.beam.jai.VirtualBandOpImage;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.StringUtils;

import javax.media.jai.PlanarImage;
import javax.media.jai.operator.ConstantDescriptor;
import java.awt.Color;
import java.awt.image.Raster;
import java.io.IOException;

@OperatorMetadata(alias = "FUB.Water", authors = "Thomas Schroeder, Michael Schaale",
                  copyright = "Institute for Space Sciences (WeW), Freie Universitaet Berlin",
                  version = "2.2",
                  description = "FUB/WeW WATER Processor to retrieve case II water properties and atmospheric properties")
public class WaterProcessorOp extends PixelOperator {

    public static final String[] OUTPUT_CONCENTRATION_BAND_NAMES = {
            "algal_2",
            "yellow_subs",
            "total_susp"
    };
    public static final String[] OUTPUT_OPTICAL_DEPTH_BAND_NAMES = {
            "aero_opt_thick_440",
            "aero_opt_thick_550",
            "aero_opt_thick_670",
            "aero_opt_thick_870"
    };
    public static final String[] OUTPUT_REFLECTANCE_BAND_NAMES = {
            "reflec_1",
            "reflec_2",
            "reflec_3",
            "reflec_4",
            "reflec_5",
            "reflec_6",
            "reflec_7",
            "reflec_9"
    };
    private static final String SUSPECT_FLAG_NAME = "l1_flags.SUSPECT";
    private static final String SUSPECT_EXPRESSION_TERM = "and not " + SUSPECT_FLAG_NAME;

    private static String[] output_concentration_band_descriptions = {
            "Chlorophyll 2 content",
            "Yellow substance",
            "Total suspended matter"
    };
    private static String[] output_optical_depth_band_descriptions = {
            "Aerosol optical thickness",
            "Aerosol optical thickness",
            "Aerosol optical thickness",
            "Aerosol optical thickness"
    };

    private static String[] output_reflectance_band_descriptions = {
            "RS reflectance",
            "RS reflectance",
            "RS reflectance",
            "RS reflectance",
            "RS reflectance",
            "RS reflectance",
            "RS reflectance",
            "RS reflectance"
    };

    private static String[] output_concentration_band_units = {
            "log10(mg/m^3)",
            "log10(1/m)",
            "log10(g/m^3)"
    };

    private static String[] output_optical_depth_band_units = {
            "1",
            "1",
            "1",
            "1"
    };

    private static String[] output_reflectance_band_units = {
            "1/sr",
            "1/sr",
            "1/sr",
            "1/sr",
            "1/sr",
            "1/sr",
            "1/sr",
            "1/sr"
    };

    // Wavelengths for the water leaving reflectances rho_w
    private static float[] tau_lambda = {
            440.00f, 550.00f, 670.00f, 870.00f
    };

    // Wavelengths for the water leaving reflectances rho_w
    private static float[] rho_w_lambda = {
            412.50f, 442.50f, 490.00f, 510.00f,
            560.00f, 620.00f, 665.00f, 708.75f
    };

    // Bandwidth for the water leaving reflectances rho_w
    private static float[] rho_w_bandw = {
            10.00f, 10.00f, 10.00f, 10.00f,
            10.00f, 10.00f, 10.00f, 10.00f
    };

    private static final String result_flags_name = "result_flags";

    // Mask value to be written if inversion fails
    private static final float result_mask_value = 5.0f;

    private static final String[] result_error_texts = {
            "Pixel was a priori masked out",
            "CHL retrieval failure (input)",
            "CHL retrieval failure (output)",
            "YEL retrieval failure (input)",
            "YEL retrieval failure (output)",
            "TSM retrieval failure (input)",
            "TSM retrieval failure (output)",
            "Atmospheric correction failure (input)",
            "Atmospheric correction failure (output)"
    };

    private static final String[] result_error_names = {
            "LEVEL1b_MASKED",
            "CHL_IN",
            "CHL_OUT",
            "YEL_IN",
            "YEL_OUT",
            "TSM_IN",
            "TSM_OUT",
            "ATM_IN",
            "ATM_OUT"
    };
    public static final int[] RESULT_ERROR_VALUES = {
            0x00000001,
            0x00000002,
            0x00000004,
            0x00000008,
            0x00000010,
            0x00000020,
            0x00000040,
            0x00000080,
            0x00000100
    };

    private static final float[] result_error_transparencies = {
            0.0f,
            0.5f,
            0.5f,
            0.5f,
            0.5f,
            0.5f,
            0.5f,
            0.5f,
            0.5f
    };

    private static final String[] source_raster_names = new String[]{
            EnvisatConstants.MERIS_L1B_RADIANCE_1_BAND_NAME,  // source sample index  0   radiance_1
            EnvisatConstants.MERIS_L1B_RADIANCE_2_BAND_NAME,  // source sample index  1   radiance_2
            EnvisatConstants.MERIS_L1B_RADIANCE_3_BAND_NAME,  // source sample index  2   radiance_3
            EnvisatConstants.MERIS_L1B_RADIANCE_4_BAND_NAME,  // source sample index  3   radiance_4
            EnvisatConstants.MERIS_L1B_RADIANCE_5_BAND_NAME,  // source sample index  4   radiance_5
            EnvisatConstants.MERIS_L1B_RADIANCE_6_BAND_NAME,  // source sample index  5   radiance_6
            EnvisatConstants.MERIS_L1B_RADIANCE_7_BAND_NAME,  // source sample index  6   radiance_7
            EnvisatConstants.MERIS_L1B_RADIANCE_8_BAND_NAME,  // source sample index  7   radiance_8
            EnvisatConstants.MERIS_L1B_RADIANCE_9_BAND_NAME,  // source sample index  8   radiance_9
            EnvisatConstants.MERIS_L1B_RADIANCE_10_BAND_NAME, // source sample index  9   radiance_10
            EnvisatConstants.MERIS_L1B_RADIANCE_11_BAND_NAME, // source sample index 10   radiance_11
            EnvisatConstants.MERIS_L1B_RADIANCE_12_BAND_NAME, // source sample index 11   radiance_12
            EnvisatConstants.MERIS_L1B_RADIANCE_13_BAND_NAME, // source sample index 12   radiance_13
            EnvisatConstants.MERIS_L1B_RADIANCE_14_BAND_NAME, // source sample index 13   radiance_14
            EnvisatConstants.MERIS_L1B_RADIANCE_15_BAND_NAME, // source sample index 14   radiance_15
            EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME,         // source sample index 15   l1_flags
            EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[6],   // source sample index 16   sun_zenith
            EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[7],   // source sample index 17   sun_azimuth
            EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[8],   // source sample index 18   view_zenith
            EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[9],   // source sample index 19   view_azimuth
            EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[10],  // source sample index 20   zonal_wind
            EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[11],  // source sample index 21   merid_wind
            EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[12],  // source sample index 22   atm_press
            EnvisatConstants.MERIS_TIE_POINT_GRID_NAMES[13]   // source sample index 23   ozone
    };
    private static final int source_sample_index_sun_zenith = 16;
    private static final int source_sample_index_sun_azimuth = 17;
    private static final int source_sample_index_view_zenith = 18;
    private static final int source_sample_index_view_azimuth = 19;
    private static final int source_sample_index_zonal_wind = 20;
    private static final int source_sample_index_merid_wind = 21;
    private static final int source_sample_index_atm_press = 22;
    private static final int source_sample_index_ozone = 23;

    private float[] solarFlux;

    private Band[] inputBands = new Band[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
    private Raster validMaskData;

    @SourceProduct(label = "Source product",
                   description = "The MERIS L1b or L1P source product used for the processing.")
    private Product sourceProduct;

    @Parameter(description = "Whether chlorophyll-a concentration band shall be computed", defaultValue = "true",
               label = "Compute chlorophyll-a concentration band")
    private boolean computeCHL;

    @Parameter(description = "Whether yellow substances band shall be computed", defaultValue = "true",
               label = "Compute yellow substances band")
    private boolean computeYS;

    @Parameter(description = "Whether total suspended matter band shall be computed", defaultValue = "true",
               label = "Compute total suspended matter band")
    private boolean computeTSM;

    @Parameter(description = "Whether atmospheric correction bands shall be computed", defaultValue = "true",
               label = "Compute water leaving reflectances and AOT bands")
    private boolean computeAtmCorr;

    @Parameter(description = "Expert parameter. Performs a check whether the '" + SUSPECT_FLAG_NAME + "' shall be considered in an expression." +
                             "This parameter is only considered when the expression contains the term '" + SUSPECT_EXPRESSION_TERM + "'",
               defaultValue = "true", label = "Check whether '" + SUSPECT_FLAG_NAME + "' is valid")
    private boolean checkWhetherSuspectIsValid;

    @Parameter(description = "Band maths expression which defines valid pixels. If the expression is empty," +
                             "all pixels will be considered.",
               defaultValue = "not l1_flags.GLINT_RISK and not l1_flags.BRIGHT and not l1_flags.INVALID " + SUSPECT_EXPRESSION_TERM,
               label = "Use valid pixel expression")
    private String expression;

    @Override
    protected void computePixel(final int xpos, final int ypos, Sample[] sourceSamples, WritableSample[] targetSamples) {

        // If set to -1.0f : NN input and output ranges are checked
        // If set to +1.0f : NN input and output ranges are NOT checked
        float aset = -1.0f;

        int width = 1;
        int nbands = inputBands.length;

        // allocate memory for a multispectral scan line
        float[] toa = new float[nbands];

        // allocate memory for the flags
        int[] resultFlags = new int[width];

        // Ozone data
        double[] wavelength = new double[nbands];
        double[] exO3 = new double[nbands];

        // local variables
        double d2r;
        float dazi;
        int k;
        int l;
        int ls = 0;
        int n;
        float[] a = new float[width];

        int inodes;
        int onodes;
        int onodes_1;
        int onodes_2;
        int stage;

        float[][] ipixel = new float[2][1];
        float[][] ipixels = new float[2][1];
        float[][] opixel = new float[2][1];

        // Load the wavelengths and ozone spectral extinction coefficients
        //
        for (int i = 0; i < nbands; i++) {
            wavelength[i] = inputBands[i].getSpectralWavelength();
            exO3[i] = WaterProcessorOzone.O3excoeff(wavelength[i]);
        }

        d2r = Math.acos(-1.0) / 180.0;

        // Get the number of I/O nodes in advance
        inodes = NN_YellowSubstance.compute(ipixel, -1, opixel, 1, width, resultFlags, 0, a);
        // implicit atm.corr.
        onodes_1 = NN_YellowSubstance.compute(ipixel, 1, opixel, -1, width, resultFlags, 0, a);
        // explicit atm.corr.
        onodes_2 = NN_AtmCorr.compute(ipixel, 1, opixel, -1, width, resultFlags, 0, a);

        int num_toa = 12;
        int output_planes = 0;
        if (computeCHL) {
            output_planes++;
        }
        if (computeYS) {
            output_planes++;
        }
        if (computeTSM) {
            output_planes++;
        }
        if (computeAtmCorr) {
            output_planes += OUTPUT_OPTICAL_DEPTH_BAND_NAMES.length + OUTPUT_REFLECTANCE_BAND_NAMES.length;
        }
        float[] top = new float[num_toa];
        float[] tops = new float[num_toa];
        double[] o3f = new double[num_toa];
        float[] sof = new float[num_toa];
        float[] aux = new float[2];
        float[] geo = new float[4];
        float[] result = new float[output_planes];

        // First the TOA radiances
        for (n = 0; n < inputBands.length; n++) {
            toa[n] = sourceSamples[n].getFloat();
        } // n

        // Second the auxiliary data
        float sza = sourceSamples[source_sample_index_sun_zenith].getFloat();
        float saa = sourceSamples[source_sample_index_sun_azimuth].getFloat();
        float vza = sourceSamples[source_sample_index_view_zenith].getFloat();
        float vaa = sourceSamples[source_sample_index_view_azimuth].getFloat();
        float zw = sourceSamples[source_sample_index_zonal_wind].getFloat();
        float mw = sourceSamples[source_sample_index_merid_wind].getFloat();
        float press = sourceSamples[source_sample_index_atm_press].getFloat();
        float o3 = sourceSamples[source_sample_index_ozone].getFloat();

        final int x = 0;

        resultFlags[x] = 0;
        int resultFlagsNN = 0;

        // Exclude pixels from processing if the following l1flags mask becomes true
        if (validMaskData.getSample(xpos, ypos, 0) == 0) {
            resultFlags[x] = RESULT_ERROR_VALUES[0];
        }

        // *********************
        // * STAGE 0
        // *********************

        // Get the toa reflectances for selected bands
        // and normalize ozone
        //
        l = 0;
        final double TOTAL_OZONE_DU_MOMO = 344.0;
        for (n = 0; n <= 6; n++, l++) {
            tops[l] = toa[n];
            sof[l] = solarFlux[n];
            top[l] = toa[n] / solarFlux[n];
            o3f[l] = Math.exp(-(TOTAL_OZONE_DU_MOMO - o3) * exO3[n] / 1000.0 * (1.0 / Math.cos(
                    (double) vza * d2r) + 1.0 / Math.cos((double) sza * d2r)));
            top[l] *= o3f[l];
        }
        for (n = 8; n <= 9; n++, l++) {
            tops[l] = toa[n];
            sof[l] = solarFlux[n];
            top[l] = toa[n] / solarFlux[n];
            o3f[l] = Math.exp(-(TOTAL_OZONE_DU_MOMO - o3) * exO3[n] / 1000.0 * (1.0 / Math.cos(
                    (double) vza * d2r) + 1.0 / Math.cos((double) sza * d2r)));
            top[l] *= o3f[l];
        }
        for (n = 11; n <= 13; n++, l++) {
            tops[l] = toa[n];
            sof[l] = solarFlux[n];
            top[l] = toa[n] / solarFlux[n];
            o3f[l] = Math.exp(-(TOTAL_OZONE_DU_MOMO - o3) * exO3[n] / 1000.0 * (1.0 / Math.cos(
                    (double) vza * d2r) + 1.0 / Math.cos((double) sza * d2r)));
            top[l] *= o3f[l];
        }

        // Get the wind speed
        aux[0] = (float) Math.sqrt((double) (zw * zw + mw * mw));
        // Get the pressure
        aux[1] = press;

        // Adjust the azimuth difference
        dazi = vaa - saa;

        while (dazi <= -180.0f) {
            dazi += 360.0f;
        }
        while (dazi > 180.0f) {
            dazi -= 360.0f;
        }
        float tmp = dazi;
        if (tmp >= 0.0f) {
            dazi = +180.0f - dazi;
        }
        if (tmp < 0.0f) {
            dazi = -180.0f - dazi;
        }

        // Get cos(sunzen)
        geo[0] = (float) Math.cos((double) sza * d2r);

        // And now transform into cartesian coordinates
        geo[1] = (float) (Math.sin((double) vza * d2r) * Math.cos((double) dazi * d2r)); // obs_x
        geo[2] = (float) (Math.sin((double) vza * d2r) * Math.sin((double) dazi * d2r)); // obs_y
        geo[3] = (float) (Math.cos((double) vza * d2r));                            // obs_z

        // *********************
        // * STAGE 1-4
        // *********************

        onodes = onodes_1; // They differ !!

        ipixel = new float[inodes][width];
        ipixels = new float[inodes][width];
        opixel = new float[onodes][width];

        // load the TOA reflectances
        for (l = 0; l < num_toa; l++) {
            ipixel[l][x] = top[l];
        }

        // get the wind speed and pressure
        ipixel[l++][x] = aux[0];
        ipixel[l++][x] = aux[1];

        // get cos(sunzen), x, yPos, z
        ipixel[l++][x] = geo[0];
        ipixel[l++][x] = geo[1];
        ipixel[l++][x] = geo[2];
        ipixel[l++][x] = geo[3];

        // Check against range limits inside the network
        // recall if the value of a[x] is set to -1.0f.
        //
        // This results in the application of the flag
        // 'RESULT_ERROR_VALUE[]' to the 'resultFlagsNN'
        a[x] = aset;

        // Save input pixel
        ls = l;
        for (l = 0; l < ls; l++) {
            ipixels[l][x] = ipixel[l][x];
        }

        int resultCounter = 0;

        if (computeCHL) {
            // Run the 1-step chlorophyll network;
            stage = 1;
            NN_CHL.compute(ipixel, inodes, opixel, onodes, width, resultFlags, 0, a);

            // Input range failure
            if ((a[x] > -2.1) && (a[x] < -1.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage - 1];
            }
            // Output range failure
            if ((a[x] > -19.1) && (a[x] < -18.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage];
            }
            // Input AND Output range failure
            if ((a[x] > -22.1) && (a[x] < -21.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage - 1];
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage];
            }
            result[resultCounter++] = opixel[0][x];
        }
        if (computeYS) {
            // Run the 1-step yellow substance network;
            stage = 2;
            // reload the pixel
            for (l = 0; l < ls; l++) {
                ipixel[l][x] = ipixels[l][x];
            }
            a[x] = aset;

            NN_YellowSubstance.compute(ipixel, inodes, opixel, onodes, width, resultFlags, 0, a);

            // Input range failure
            if ((a[x] > -2.1) && (a[x] < -1.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage - 1];
            }
            // Output range failure
            if ((a[x] > -19.1) && (a[x] < -18.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage];
            }
            // Input AND Output range failure
            if ((a[x] > -22.1) && (a[x] < -21.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage - 1];
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage];
            }
            result[resultCounter++] = opixel[0][x];
        }
        if (computeTSM) {
            // Run the 1-step total suspended matter network;
            stage = 3;

            // reload the pixel
            for (l = 0; l < ls; l++) {
                ipixel[l][x] = ipixels[l][x];
            }
            a[x] = aset;

            NN_TSM.compute(ipixel, inodes, opixel, onodes, width, resultFlags, 0, a);

            // Input range failure
            if ((a[x] > -2.1) && (a[x] < -1.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage - 1];
            }
            // Output range failure
            if ((a[x] > -19.1) && (a[x] < -18.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage];
            }
            // Input AND Output range failure
            if ((a[x] > -22.1) && (a[x] < -21.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage - 1];
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage];
            }
            result[resultCounter++] = opixel[0][x];
        }
        if (computeAtmCorr) {
            // Run part 1 of the 2-step atm.corr. network;
            stage = 4;
            onodes = onodes_2;
            opixel = new float[onodes][width];

            // reload the pixel
            for (l = 0; l < ls; l++) {
                ipixel[l][x] = ipixels[l][x];
            }
            a[x] = aset;

            NN_AtmCorr.compute(ipixel, inodes, opixel, onodes, width, resultFlags, 0, a);

            // Input range failure
            if ((a[x] > -2.1) && (a[x] < -1.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage - 1];
            }
            // Output range failure
            if ((a[x] > -19.1) && (a[x] < -18.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage];
            }
            // Input AND Output range failure
            if ((a[x] > -22.1) && (a[x] < -21.9)) {
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage - 1];
                resultFlagsNN |= RESULT_ERROR_VALUES[2 * stage];
            }
            //System.out.println(" --> " + resultFlagsNN[x]);

            // The aots
            final int num_msl = 8;
            for (int i = num_msl; i < onodes; i++) {
                result[resultCounter + i - num_msl] = opixel[i][x];
            }
            for (int i = 0; i < num_msl; i++) {
                final int numOfSpectralAerosolOpticalDepths = 4;
                result[resultCounter + numOfSpectralAerosolOpticalDepths + i] = opixel[i][x];
            }
        }
        // Now check for error flags !
        // If set, set output vector to mask value !
        if (resultFlags[x] != 0) {
            for (n = 0; n < output_planes; n++) {
                result[n] = result_mask_value;
            }
        }
        // Combine result flags
        resultFlags[x] |= resultFlagsNN;

        // Set sample values in target product
        //
        for (n = 0; n < output_planes; n++) {
            targetSamples[n].set(result[n]);
        }
        targetSamples[output_planes].set(resultFlags[0]);
    }

    private void checkWhetherSuspectIsValid() {
        if (!expression.contains(SUSPECT_EXPRESSION_TERM)) {
            return;
        }
        final int height = sourceProduct.getSceneRasterHeight();
        final int width = sourceProduct.getSceneRasterWidth();

        int k;// Some Level 1b scenes mark almost all pixels as 'suspect'. This is obviously nonsense.
        // Because we would like to make use of the suspect flag in mask mask_to_be_used we do
        // check first if it behaves fine, ie the number of suspect pixels for one line in the
        // middle of the scene should be below 50 % . Else we do not make use of the suspect flag
        // in the mask mask_to_be_used.

        // Grab a line in the middle of the scene
        final Band l1FlagsInputBand = sourceProduct.getBand(EnvisatConstants.MERIS_L1B_FLAGS_DS_NAME);
        int[] l1Flags = new int[width];
        final int halfHeight = height / 2;
        try {
            l1FlagsInputBand.readPixels(0, halfHeight, width, 1, l1Flags);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // The input type pattern for ICOL products
        final String ICOL_PATTERN = "MER_.*1N";
        boolean icolMode = sourceProduct.getProductType().matches(ICOL_PATTERN);
        if (icolMode) {
            expression = expression.replace(SUSPECT_EXPRESSION_TERM, "");
            System.out.println("--- Input product is of type icol ---");
            System.out.println("--- Switching to relaxed mask. ---");
        } else {
            final PlanarImage validMaskImage = createValidMaskImage(sourceProduct, SUSPECT_FLAG_NAME);
            final Raster validData = validMaskImage.getData();
            k = 0;
            // Now sum up the cases which signal a suspect behaviour
            for (int i = 0; i < width; i++) {
                if (validData.getSample(i, halfHeight, 0) != 0) {
                    k++;
                }
            }
            // more than 50 percent ?
            if (k >= width / 2) {
                // Do not make use of the suspect flag
                expression = expression.replace(SUSPECT_EXPRESSION_TERM, "");
//                maskToBeUsed = mask_to_be_used;
                final float percent = (float) k / (float) width * 100.0f;
                System.out.println("--- " + percent + " % of the scan line are marked as suspect ---");
                System.out.println("--- Switching to relaxed mask. ---");
            }
        }
    }

    /*
    * Reads the solar spectral fluxes for all MERIS L1b bands.
    *
    * Sometimes the file do not contain solar fluxes. As they do
    * show heavy variations over the year or for slight wavelength
    * shifts we do use some defaults if necessary.
    */
    private float[] getSolarFlux(Product product, Band[] bands) {
        float[] dsf = getSolarFluxFromMetadata(product);
        if (dsf == null) {
            dsf = new float[bands.length];
            final double[] defsol = new double[]{
                    1670.5964, 1824.1444, 1874.9883,
                    1877.6682, 1754.7749, 1606.6401,
                    1490.0026, 1431.8726, 1369.2035,
                    1231.7164, 1220.0767, 1144.9675,
                    932.3497, 904.8193, 871.0908
            };
            for (int i = 0; i < bands.length; i++) {
                Band band = bands[i];
                dsf[i] = band.getSolarFlux();
                if (dsf[i] <= 0.0) {
                    dsf[i] = (float) defsol[i];
                }
            }
        }
        return dsf;
    }

    private float[] getSolarFluxFromMetadata(Product product) {
        MetadataElement metadataRoot = product.getMetadataRoot();
        MetadataElement gadsElem = metadataRoot.getElement("Scaling_Factor_GADS");
        if (gadsElem != null) {
            MetadataAttribute solarFluxAtt = gadsElem.getAttribute("sun_spec_flux");
            if (solarFluxAtt != null) {
                return (float[]) solarFluxAtt.getDataElems();
            }
        }
        return null;
    }

    @Override
    protected void prepareInputs() throws OperatorException {
        super.prepareInputs();
        for (int i = 0; i < inputBands.length; i++) {
            String radianceBandName = "radiance_" + (i + 1);
            Band radianceBand = sourceProduct.getBand(radianceBandName);
            if (radianceBand == null) {
                throw new OperatorException(String.format("Missing input band '%s'.", radianceBandName));
            }
            if (radianceBand.getSpectralWavelength() <= 0.0) {
                throw new OperatorException(String.format("Input band '%s' does not have wavelength information.", radianceBandName));
            }
            inputBands[i] = radianceBand;
        }
        solarFlux = getSolarFlux(sourceProduct, inputBands);
        if (checkWhetherSuspectIsValid) {
            checkWhetherSuspectIsValid();
        }
        final PlanarImage validMaskImage = createValidMaskImage(sourceProduct, expression);
        validMaskData = validMaskImage.getData();
    }

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) {
        super.configureTargetProduct(productConfigurer);
        productConfigurer.copyMetadata();
        final Product sourceProduct = productConfigurer.getSourceProduct();
        final Product targetProduct = productConfigurer.getTargetProduct();

        targetProduct.setProductType(getOutputProductType());

        int sceneWidth = sourceProduct.getSceneRasterWidth();
        int sceneHeight = sourceProduct.getSceneRasterHeight();

        if (computeCHL) {
            addConcentrationBand(targetProduct, sceneWidth, sceneHeight, 0);
        }
        if (computeYS) {
            addConcentrationBand(targetProduct, sceneWidth, sceneHeight, 1);
        }
        if (computeTSM) {
            addConcentrationBand(targetProduct, sceneWidth, sceneHeight, 2);
        }
        if (computeAtmCorr) {
            addOpticalDepthBands(targetProduct, sceneWidth, sceneHeight);
            addReflectanceBands(targetProduct, sceneWidth, sceneHeight);
        }

        ProductUtils.copyFlagBands(sourceProduct, targetProduct, true);

        if (!targetProduct.containsBand(EnvisatConstants.MERIS_AMORGOS_L1B_CORR_LONGITUDE_BAND_NAME)) {
            productConfigurer.copyBands(EnvisatConstants.MERIS_AMORGOS_L1B_CORR_LONGITUDE_BAND_NAME);
        }
        if (!targetProduct.containsBand(EnvisatConstants.MERIS_AMORGOS_L1B_CORR_LATITUDE_BAND_NAME)) {
            productConfigurer.copyBands(EnvisatConstants.MERIS_AMORGOS_L1B_CORR_LATITUDE_BAND_NAME);
        }
        productConfigurer.copyBands(EnvisatConstants.MERIS_AMORGOS_L1B_ALTIUDE_BAND_NAME);

        FlagCoding resultFlagCoding = createResultFlagCoding();
        targetProduct.getFlagCodingGroup().add(resultFlagCoding);
        final Band resultFlagsOutputBand = targetProduct.addBand(result_flags_name, ProductData.TYPE_UINT16);
        resultFlagsOutputBand.setDescription("FUB/WeW WATER plugin specific flags");
        resultFlagsOutputBand.setSampleCoding(resultFlagCoding);

        productConfigurer.copyMasks();

        String flagNamePrefix = result_flags_name + ".";
        addMasksToTargetProduct(targetProduct, sceneWidth, sceneHeight, flagNamePrefix);
    }

    private void addMasksToTargetProduct(Product targetProduct, int sceneWidth, int sceneHeight, String flagNamePrefix) {
        ProductNodeGroup<Mask> maskGroup = targetProduct.getMaskGroup();
        Color[] colors = new Color[]{
                Color.cyan, Color.green, Color.green, Color.yellow, Color.yellow,
                Color.orange, Color.orange, Color.blue, Color.blue
        };
        for (int i = 0; i < result_error_names.length; i++) {
            maskGroup.add(Mask.BandMathsType.create(result_error_names[i].toLowerCase(), result_error_texts[i],
                                                    sceneWidth, sceneHeight, flagNamePrefix + result_error_names[i],
                                                    colors[i], result_error_transparencies[i]));

        }
    }

    private void addReflectanceBands(Product targetProduct, int sceneWidth, int sceneHeight) {
        for (int i = 0; i < OUTPUT_REFLECTANCE_BAND_NAMES.length; i++) {
            final Band band = createBand(OUTPUT_REFLECTANCE_BAND_NAMES[i], sceneWidth, sceneHeight);
            band.setDescription(output_reflectance_band_descriptions[i]);
            band.setUnit(output_reflectance_band_units[i]);
            band.setSpectralWavelength(rho_w_lambda[i]);
            band.setSpectralBandwidth(rho_w_bandw[i]);
            band.setSpectralBandIndex(i);
            band.setNoDataValue(result_mask_value);
            band.setNoDataValueUsed(true);
            targetProduct.addBand(band);
        }
    }

    private void addOpticalDepthBands(Product targetProduct, int sceneWidth, int sceneHeight) {
        for (int i = 0; i < OUTPUT_OPTICAL_DEPTH_BAND_NAMES.length; i++) {
            final Band band = createBand(OUTPUT_OPTICAL_DEPTH_BAND_NAMES[i], sceneWidth, sceneHeight);
            band.setDescription(output_optical_depth_band_descriptions[i]);
            band.setUnit(output_optical_depth_band_units[i]);
            band.setSpectralWavelength(tau_lambda[i]);
            band.setSpectralBandIndex(i);
            band.setNoDataValue(result_mask_value);
            band.setNoDataValueUsed(true);
            targetProduct.addBand(band);
        }
    }

    private void addConcentrationBand(Product targetProduct, int sceneWidth, int sceneHeight, int concentrationBandIndex) {
        final Band band = createBand(OUTPUT_CONCENTRATION_BAND_NAMES[concentrationBandIndex], sceneWidth, sceneHeight);
        band.setDescription(output_concentration_band_descriptions[concentrationBandIndex]);
        band.setUnit(output_concentration_band_units[concentrationBandIndex]);
        band.setNoDataValueUsed(true);
        band.setNoDataValue(5.0);
        targetProduct.addBand(band);
    }

    private Band createBand(String bandName, int sceneWidth, int sceneHeight) {
        final Band band = new Band(bandName, ProductData.TYPE_FLOAT32, sceneWidth, sceneHeight);
        band.setScalingOffset(0.0);
        band.setScalingFactor(1.0);
        band.setSpectralBandIndex(0);
        return band;
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        configureSamples(sampleConfigurer, source_raster_names);
    }

    @Override
    protected void configureTargetSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        String[] bandNames = new String[0];
        if (computeCHL) {
            bandNames = StringUtils.addToArray(bandNames, OUTPUT_CONCENTRATION_BAND_NAMES[0]);
        }
        if (computeYS) {
            bandNames = StringUtils.addToArray(bandNames, OUTPUT_CONCENTRATION_BAND_NAMES[1]);
        }
        if (computeTSM) {
            bandNames = StringUtils.addToArray(bandNames, OUTPUT_CONCENTRATION_BAND_NAMES[2]);
        }
        if (computeAtmCorr) {
            bandNames = StringUtils.addArrays(bandNames, OUTPUT_OPTICAL_DEPTH_BAND_NAMES);
            bandNames = StringUtils.addArrays(bandNames, OUTPUT_REFLECTANCE_BAND_NAMES);
        }
        bandNames = StringUtils.addToArray(bandNames, result_flags_name);
        configureSamples(sampleConfigurer, bandNames);
    }

    private void configureSamples(SampleConfigurer sampleConfigurer, String[] bandNames) {
        for (int i = 0; i < bandNames.length; i++) {
            sampleConfigurer.defineSample(i, bandNames[i]);
        }
    }

    public static FlagCoding createResultFlagCoding() {
        FlagCoding resultFlagCoding = new FlagCoding(result_flags_name);
        resultFlagCoding.setDescription("RESULT Flag Coding");
        for (int i = 0; i < result_error_names.length; i++) {
            MetadataAttribute attribute = new MetadataAttribute(result_error_names[i], ProductData.TYPE_INT32);
            attribute.getData().setElemInt(RESULT_ERROR_VALUES[i]);
            attribute.setDescription(result_error_texts[i]);
            resultFlagCoding.addAttribute(attribute);
        }
        return resultFlagCoding;
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(WaterProcessorOp.class);
        }
    }

    private String getOutputProductType() throws OperatorException {
        String sourceType = sourceProduct.getProductType();
        if (sourceType != null) {
            return String.format("%s_FLH_MCI", sourceType);
        } else {
            return "FLH_MCI";
        }
    }

    private PlanarImage createValidMaskImage(Product product, String expression) {
        if (StringUtils.isNullOrEmpty(expression)) {
            return createEmptyMask(product);
        }
        if (product.isCompatibleBandArithmeticExpression(expression)) {
            return VirtualBandOpImage.create(expression, ProductData.TYPE_UINT8, 0, product, ResolutionLevel.MAXRES);
        } else {
            String msg = String.format("Parameter 'expression' is not compatible with the source product. Expression is '%s'", expression);
            throw new OperatorException(msg);
        }
    }

    private PlanarImage createEmptyMask(Product product) {
        return ConstantDescriptor.create((float) product.getSceneRasterWidth(),
                                         (float) product.getSceneRasterHeight(),
                                         new Byte[]{-1}, null);
    }

}
