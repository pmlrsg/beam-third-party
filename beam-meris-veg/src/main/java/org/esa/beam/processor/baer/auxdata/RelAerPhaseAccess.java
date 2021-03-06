/*
 * $Id: RelAerPhaseAccess.java,v 1.1.1.1 2005/02/15 11:13:36 meris Exp $
 *
 * Copyright (C) 2002,2003 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the 
 * Free Software Foundation. This program is distributed in the hope it will 
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty 
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 * See the GNU General Public License for more details.
 */
package org.esa.beam.processor.baer.auxdata;

public interface RelAerPhaseAccess {

    /**
     * Retrieves the relative aerosol phase function coefficients. The array
     * contains three coefficients:
     *  a[0] - offset
     *  a[1] - linear term
     *  a[2] - quadratic term
     * @return the coefficients array
     */
    public double[] getRelativeAerosolPhaseCoefficients();
}
