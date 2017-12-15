// ImageJ stuff
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import ij.measure.Calibration;
import ij.plugin.CompositeConverter;

import java.awt.Color;

// Java utilities
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Vector;
import java.util.Arrays;
import java.util.List;

// For remote SSH connections
import com.jcraft.jsch.Session;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

// HDF5 stuff
import ncsa.hdf.hdf5lib.exceptions.HDF5Exception;
import ch.systemsx.cisd.hdf5.HDF5FloatStorageFeatures;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import ch.systemsx.cisd.hdf5.IHDF5WriterConfigurator;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import ch.systemsx.cisd.hdf5.HDF5DataSetInformation;
import ch.systemsx.cisd.base.mdarray.MDFloatArray;


public class UnetTools {

/*======================================================================*/
/*!
 *   Remove the file with given path from the remote host via sftp.
 *
 *   \param path The absolute file path on the remote host
 *   \param session The open SSH session
 *   \param job If not null, progress will be reported to the given UnetJob
 *     otherwise it will be shown in the ImageJ status bar
 *
 *   \exception JSchException if the SSH session is not open
 *   \exception IOException if the remote file could not be removed
 *   \exception InterruptedException if the user requests thread termination
 *   \exception SftpException if the Sftp connection fails
 */
/*======================================================================*/
  public static void removeFile(String path, Session session, UnetJob job)
      throws JSchException, IOException, InterruptedException, SftpException {
    if (job != null)
        job.setTaskProgress(
            "Removing file '" + session.getHost() + ":" + path + "'", 0, 0);
    else
        IJ.showStatus("Removing file '" + session.getHost() + ":" + path + "'");
    IJ.log(session.getUserName() + "@" + session.getHost() + " $ rm \"" +
           path + "\"");
    Channel channel = session.openChannel("sftp");
    channel.connect();
    ChannelSftp c = (ChannelSftp)channel;
    c.rm(path);
    channel.disconnect();
    if (job != null) job.setTaskProgress(1, 1);
  }

/*======================================================================*/
/*!
 *   Rename the file with given path from the remote host via sftp.
 *
 *   \param oldpath The absolute file path on the remote host
 *   \param newpath The new absolute file path on the remote host
 *   \param session The open SSH session
 *   \param job If not null, progress will be reported to the given UnetJob
 *     otherwise it will be shown in the ImageJ status bar
 *
 *   \exception JSchException if the SSH session is not open
 *   \exception IOException if the remote file could not be removed
 *   \exception InterruptedException if the user requests thread termination
 *   \exception SftpException if the Sftp connection fails
 */
/*======================================================================*/
  public static void renameFile(
      String oldpath, String newpath, Session session, UnetJob job)
      throws JSchException, IOException, InterruptedException, SftpException {
    if (job != null)
        job.setTaskProgress(
            "Renaming file '" + session.getHost() + ":" + oldpath + "' to '" +
            newpath + "'", 0, 0);
    else
        IJ.showStatus(
            "Renaming file '" + session.getHost() + ":" + oldpath + "' to '" +
            newpath + "'");
    IJ.log(session.getUserName() + "@" + session.getHost() + " $ mv \"" +
           oldpath + "\" \"" + newpath + "\"");
    Channel channel = session.openChannel("sftp");
    channel.connect();
    ChannelSftp c = (ChannelSftp)channel;
    c.rename(oldpath, newpath);
    channel.disconnect();
    if (job != null) job.setTaskProgress(1, 1);
  }

/*======================================================================*/
/*!
 *   Remove the folder with given path from the remote host via sftp.
 *   The folder must be empty before it can be removed.
 *
 *   \param path The absolute folder path on the remote host
 *   \param session The open SSH session
 *   \param job If not null, progress will be reported to the given UnetJob
 *     otherwise it will be shown in the ImageJ status bar
 *
 *   \exception JSchException if the SSH session is not open
 *   \exception IOException if the remote folder could not be removed
 *   \exception InterruptedException if the user requests thread termination
 *   \exception SftpException if the Sftp connection fails
 */
/*======================================================================*/
  public static void removeFolder(String path, Session session, UnetJob job)
      throws JSchException, IOException, InterruptedException, SftpException {
    if (job != null)
        job.setTaskProgress(
            "Removing folder '" + session.getHost() + ":" + path + "'", 0, 0);
    else
        IJ.showStatus(
            "Removing folder '" + session.getHost() + ":" + path + "'");
    IJ.log(session.getUserName() + "@" + session.getHost() + " $ rmdir \"" +
           path + "\"");
    Channel channel = session.openChannel("sftp");
    channel.connect();
    ChannelSftp c = (ChannelSftp)channel;
    c.rmdir(path);
    channel.disconnect();
    if (job != null) job.setTaskProgress(1, 1);
  }

/*======================================================================*/
/*!
 *   Upload the given local file to the remote host via sftp. Required
 *   folders on the remote host will be created and returned in reverse
 *   creation order to allow to easily clean up again later.
 *
 *   \param inFile The file to copy on the local host
 *   \param outFileName The absolute file path on the remote host
 *   \param session The open SSH session
 *   \param job If not null, progress will be reported to the given UnetJob
 *     otherwise it will be shown in the ImageJ status bar
 *
 *   \exception JSchException if the SSH session is not open
 *   \exception IOException if the file could not be copied
 *   \exception InterruptedException if the user requests thread termination
 *   \exception SftpException if the Sftp connection fails
 *
 *   \return A Vector containing the absolute paths of created folders on
 *     the remote host. The vector is sorted in reverse creation order to
 *     allow simple forward iteration for removing the folders again
 */
/*======================================================================*/
  public static Vector<String> put(
      File inFile, String outFileName, Session session, UnetJob job)
      throws JSchException, IOException, InterruptedException, SftpException {
    Channel channel = session.openChannel("sftp");
    channel.connect();
    ChannelSftp c = (ChannelSftp)channel;

    // Recursively create parent folders
    String[] folders = outFileName.split("/");
    Vector<String> createdFolders = new Vector<String>();
    String currentFolder = "/";
    c.cd("/");
    for (int i = 0; i < folders.length - 1; ++i) {
      if (folders[i].length() > 0) {
        try {
          c.cd(folders[i]);
          currentFolder += "/" + folders[i];
        }
        catch (SftpException e) {
          if (job != null)
              job.setTaskProgress(
                  "Creating folder '" + currentFolder + "/" + folders[i] +
                  "' on host '" + session.getHost() + "'", 0, 0);
          else
              IJ.showStatus(
                  "Creating folder '" + currentFolder + "/" + folders[i] +
                  "' on host '" + session.getHost() + "'");
          IJ.log(
              session.getUserName() + "@" + session.getHost() +
              " $ mkdir \"" + currentFolder + "/" + folders[i] + "\"");
          c.mkdir(folders[i]);
          c.cd(folders[i]);
          if (!currentFolder.endsWith("/")) currentFolder += "/";
          currentFolder += folders[i];
          createdFolders.add(0, currentFolder);
          if (job != null) job.setTaskProgress(1, 1);
        }
      }
    }

    // Copy the file
    if (job != null)
        job.setTaskProgress(
            "Copying '" + inFile.getName() + "' to host '" +
            session.getHost() + "'", 0, 0);
    else
        IJ.showStatus("Copying '" + inFile.getName() + "' to host '" +
                      session.getHost() + "'");
    IJ.log(
        "$ sftp \"" + inFile.getAbsolutePath() + "\" \"" +
        session.getUserName() + "@" + session.getHost() + ":" +
        session.getPort() + ":" + outFileName + "\"");
    MySftpProgressMonitor progressMonitor =
        new MySftpProgressMonitor(job);
    c.put(inFile.getAbsolutePath(), outFileName, progressMonitor,
          ChannelSftp.OVERWRITE);
    if (progressMonitor.canceled()) {
      c.rm(outFileName);
      for (int i = 0; i < createdFolders.size(); ++i)
          c.rmdir(createdFolders.get(i));
      throw new InterruptedException("Upload canceled by user");
    }
    channel.disconnect();
    if (job != null) job.setTaskProgress(1, 1);

    return createdFolders;
  }

/*======================================================================*/
/*!
 *   Fetch the remote file with given path via sftp. The file will be marked
 *   for deletion (deleteOnExit()) on Java virtual machine shutdown. So if
 *   you want to keep it you have to explicitly remove this flag from the
 *   File after the copy process.
 *
 *   \param inFileName The file to copy on the remote host
 *   \param outFile The File on the local host to create
 *   \param session The open SSH session
 *   \param job If not null, progress will be reported to the given UnetJob
 *     otherwise it will be shown in the ImageJ status bar
 *
 *   \exception JSchException if the SSH session is not open
 *   \exception IOException if the file could not be copied
 *   \exception InterruptedException if the user requests thread termination
 *   \exception SftpException if the Sftp connection fails
 */
/*======================================================================*/
  public static void get(
      String inFileName, File outFile, Session session, UnetJob job)
      throws JSchException, IOException, InterruptedException, SftpException {
    Channel channel = session.openChannel("sftp");
    channel.connect();
    ChannelSftp c = (ChannelSftp)channel;

    // Recursively create parent folders
    Vector<File> createdFolders = new Vector<File>();
    File folder = outFile.getParentFile();
    while (folder != null && !folder.isDirectory()) {
      createdFolders.add(folder);
      folder = folder.getParentFile();
    }
    for (int i = createdFolders.size() - 1; i >= 0; --i) {
      if (job != null)
          job.setTaskProgress(
              "Creating folder '" + createdFolders.get(i) + "'", 0, 0);
      else
          IJ.showStatus(
              "Creating folder '" + createdFolders.get(i) + "'");
      IJ.log("$ mkdir \"" + createdFolders.get(i) + "\"");
      if (!createdFolders.get(i).mkdir())
          throw new IOException(
              "Could not create folder '" +
              createdFolders.get(i).getAbsolutePath() + "'");
      createdFolders.get(i).deleteOnExit();
      if (job != null) job.setTaskProgress(1, 1);
    }

    // Copy the file
    if (job != null)
        job.setTaskProgress("Fetching '" + inFileName + "' from host '" +
                            session.getHost() + "'", 0, 0);
    else
        IJ.showStatus("Fetching '" + inFileName + "' from host '" +
                      session.getHost() + "'");
    IJ.log(
        "$ sftp \"" + session.getUserName() +
        "@" + session.getHost() + ":" + session.getPort() + ":" +
        inFileName + "\" \"" + outFile.getAbsolutePath() + "\"");
    MySftpProgressMonitor progressMonitor = new MySftpProgressMonitor(job);
    outFile.deleteOnExit();
    c.get(inFileName, outFile.getAbsolutePath(), progressMonitor,
          ChannelSftp.OVERWRITE);

    if (progressMonitor.canceled())
        throw new InterruptedException("Download canceled by user");

    channel.disconnect();
    if (job != null) job.setTaskProgress(1, 1);
  }

/*======================================================================*/
/*!
 *   If the given ImagePlus is a color image (stack), a new ImagePlus will be
 *   created with color components split to individual channels.
 *   For grayscale images calling this method is a noop and a reference to
 *   the input ImagePlus is returned
 *
 *   \param imp The ImagePlus to convert to a composite multi channel image
 *   \param job Task progress will be reported via this UnetJob.
 *
 *   \return The newly created ImagePlus or if no conversion was required
 *     a reference to imp
 */
/*======================================================================*/
  public static ImagePlus makeComposite(ImagePlus imp, UnetJob job) {
    if (imp.getType() != ImagePlus.COLOR_256 &&
        imp.getType() != ImagePlus.COLOR_RGB) return imp;

    job.setTaskProgress("Splitting color channels", 0, 0);
    ImagePlus out = CompositeConverter.makeComposite(imp);
    out.setTitle(imp.getTitle() + " - composite");
    out.setCalibration(imp.getCalibration());
    return out;
  }

/*======================================================================*/
/*!
 *   If the given ImagePlus is not 32Bit, a new ImagePlus will be
 *   created with the content of the given ImagePlus to 32Bit float.
 *   For 32Bit images calling this method is a noop and a reference to
 *   the input ImagePlus is returned
 *
 *   \param imp The ImagePlus to convert to float
 *   \param job Task progress will be reported via this UnetJob.
 *
 *   \return The newly created ImagePlus or if no conversion was required
 *     a reference to imp
 */
/*======================================================================*/
  public static ImagePlus convertToFloat(ImagePlus imp, UnetJob job)
      throws InterruptedException {
    if (imp.getBitDepth() == 32) return imp;

    job.setTaskProgress(
        "Converting hyperstack to float", 0, imp.getImageStackSize());
    ImagePlus out = IJ.createHyperStack(
        imp.getTitle() + " - 32-Bit", imp.getWidth(), imp.getHeight(),
        imp.getNChannels(), imp.getNSlices(), imp.getNFrames(), 32);
    out.setCalibration(imp.getCalibration());

    if (imp.getImageStackSize() == 1) {
      out.setProcessor(imp.getProcessor().duplicate().convertToFloat());
      job.setTaskProgress(1, 1);
      return out;
    }

    for (int i = 1; i <= imp.getImageStackSize(); i++) {
      if (job.interrupted())
          throw new InterruptedException("Aborted by user");
      out.getStack().setProcessor(
          imp.getStack().getProcessor(i).duplicate().convertToFloat(), i);
      job.setTaskProgress(i, imp.getImageStackSize());
    }
    return out;
  }

/*======================================================================*/
/*!
 *   If the model definition of the given UnetJob requires 2-D data, both
 *   time and z will be interpreted as time. The stack layout is changed
 *   accordingly. For 3-D models or if the image only contains one slice
 *   per time point, this method is a noop and a reference to
 *   the input ImagePlus is returned
 *
 *   \param imp The ImagePlus to rearrange
 *   \param job The UnetJob containing the model definition. Task progress
 *     will be reported via this UnetJob.
 *
 *   \return The newly created ImagePlus or if no conversion was required
 *     a reference to imp
 */
/*======================================================================*/
  public static ImagePlus fixStackLayout(ImagePlus imp, UnetJob job)
      throws InterruptedException {
    if (job.model().elementSizeUm.length == 3 || imp.getNSlices() == 1)
        return imp;
    if (!IJ.showMessageWithCancel(
            "2-D model selected", "The selected model " +
            "requires 2-D Input.\n" +
            "Applying 2-D segmentation to all slices."))
        throw new InterruptedException();

    ImagePlus out = IJ.createHyperStack(
        imp.getTitle() + " - reordered", imp.getWidth(), imp.getHeight(),
        imp.getNChannels(), 1, imp.getNSlices() * imp.getNFrames(), 32);
    Calibration cal = imp.getCalibration().copy();
    cal.pixelDepth = 1;
    out.setCalibration(cal);
    job.setTaskProgress(
        "Fixing stack layout", 0, imp.getImageStackSize());
    for (int i = 1; i <= imp.getImageStackSize(); ++i) {
      if (job.interrupted())
          throw new InterruptedException("Aborted by user");
      out.getStack().setProcessor(
          imp.getStack().getProcessor(i).duplicate(), i);
      job.setTaskProgress(i, imp.getImageStackSize());
    }
    return out;
  }

  public static ImagePlus rescaleXY(
      ImagePlus imp, int interpolationMethod, UnetJob job)
      throws InterruptedException {
    Calibration cal = imp.getCalibration().copy();
    int offs = (job.model().elementSizeUm.length == 2) ? 0 : 1;
    cal.pixelHeight = job.model().elementSizeUm[offs];
    cal.pixelWidth = job.model().elementSizeUm[offs + 1];

    float[] scales = new float[2];
    scales[0] = (float)(imp.getCalibration().pixelHeight / cal.pixelHeight);
    scales[1] = (float)(imp.getCalibration().pixelWidth / cal.pixelWidth);
    if (scales[0] == 1 && scales[1] == 1) return imp;

    job.setTaskProgress(
        "Rescaling hyperstack (xy)", 0, imp.getImageStackSize());
    IJ.log("Rescaling Hyperstack (xy) from [" +
           imp.getCalibration().pixelDepth + ", " +
           imp.getCalibration().pixelHeight + ", " +
           imp.getCalibration().pixelWidth + "] to [" +
           cal.pixelDepth + ", " +
           cal.pixelHeight + ", " +
           cal.pixelWidth + "]");

    ImagePlus out = IJ.createHyperStack(
        imp.getTitle() + " - rescaled (xy)",
        (int)(imp.getWidth() * scales[1]),
        (int)(imp.getHeight() * scales[0]), imp.getNChannels(),
        imp.getNSlices(), imp.getNFrames(), 32);
    out.setCalibration(cal);

    // Special treatment for single image
    if (imp.getImageStackSize() == 1) {
      ImageProcessor ip = imp.getProcessor().duplicate();
      ip.setInterpolationMethod(interpolationMethod);
      out.setProcessor(ip.resize(out.getWidth(), out.getHeight(), true));
      job.setTaskProgress(1, 1);
      return out;
    }

    for (int i = 1; i <= imp.getImageStackSize(); ++i) {
      if (job.interrupted())
          throw new InterruptedException("Aborted by user");
      ImageProcessor ip = imp.getStack().getProcessor(i).duplicate();
      ip.setInterpolationMethod(interpolationMethod);
      out.getStack().setProcessor(
          ip.resize(out.getWidth(), out.getHeight(), true), i);
      job.setTaskProgress(i, imp.getImageStackSize());
    }
    return out;
  }

  public static ImagePlus rescaleZ(
      ImagePlus imp, int interpolationMethod, UnetJob job)
      throws InterruptedException {
    if (job.model().elementSizeUm.length == 2 || imp.getNSlices() == 1)
        return imp;

    double scale = imp.getCalibration().pixelDepth /
        job.model().elementSizeUm[0];
    if (scale == 1) return imp;

    Calibration cal = imp.getCalibration().copy();
    cal.pixelDepth = job.model().elementSizeUm[0];
    ImagePlus out = IJ.createHyperStack(
        imp.getTitle() + " - rescaled (z)",
        imp.getWidth(), imp.getHeight(), imp.getNChannels(),
        (int)(imp.getNSlices() * scale), imp.getNFrames(), 32);
    out.setCalibration(cal);
    job.setTaskProgress(
        "Rescaling hyperstack (z)", 0, out.getImageStackSize());
    IJ.log("Rescaling Hyperstack (z) from [" +
           imp.getCalibration().pixelDepth + ", " +
           imp.getCalibration().pixelHeight + ", " +
           imp.getCalibration().pixelWidth + "] to [" +
           cal.pixelDepth + ", " +
           cal.pixelHeight + ", " +
           cal.pixelWidth + "] (scale factor = " + scale + ")");
    IJ.log("  Input shape = [" +
           imp.getNFrames() + ", " +
           imp.getNChannels() + ", " +
           imp.getNSlices() + ", " +
           imp.getHeight() + ", " +
           imp.getWidth() + "]");
    IJ.log("  Output shape = [" +
           out.getNFrames() + ", " +
           out.getNChannels() + ", " +
           out.getNSlices() + ", " +
           out.getHeight() + ", " +
           out.getWidth() + "]");

    for (int t = 1; t <= out.getNFrames(); ++t) {
      for (int z = 1; z <= out.getNSlices(); ++z) {
        double zTmp = (z - 1) / scale + 1;
        if (interpolationMethod == ImageProcessor.BILINEAR) {
          int zIn = (int)Math.floor(zTmp);
          double lambda = zTmp - zIn;
          for (int c = 1; c <= out.getNChannels(); ++c) {
            if (job.interrupted())
                throw new InterruptedException("Aborted by user");
            ImageProcessor ip = imp.getStack().getProcessor(
                imp.getStackIndex(c, zIn, t)).duplicate();
            if (lambda != 0) {
              ip.multiply(1 - lambda);
              if (zIn + 1 <= imp.getNSlices()) {
                ImageProcessor ip2 = imp.getStack().getProcessor(
                    imp.getStackIndex(c, zIn + 1, t)).duplicate();
                float[] ipData = (float[]) ip.getPixels();
                float[] ip2Data = (float[]) ip2.getPixels();
                for (int i = 0; i < imp.getHeight() * imp.getWidth(); ++i)
                    ipData[i] += lambda * ip2Data[i];
              }
            }
            out.getStack().setProcessor(ip, out.getStackIndex(c, z, t));
            job.setTaskProgress(
                out.getStackIndex(c, z, t), out.getImageStackSize());
          }
        }
        else {
          int zIn = (int)Math.round(zTmp);
          for (int c = 1; c <= out.getNChannels(); ++c) {
            if (job.interrupted())
                throw new InterruptedException("Aborted by user");
            out.getStack().setProcessor(
                imp.getStack().getProcessor(
                    imp.getStackIndex(c, zIn, t)).duplicate(),
                out.getStackIndex(c, z, t));
            job.setTaskProgress(
                out.getStackIndex(c, z, t), out.getImageStackSize());
          }
        }
      }
    }
    return out;
  }

  public static ImagePlus normalizeValues(ImagePlus imp, UnetJob job)
      throws InterruptedException {
    if (job.model().normalizationType == 0) return imp;
    ImagePlus out = imp;
    float[] scales = new float[imp.getNFrames()];
    float[] offsets = new float[imp.getNFrames()];
    for (int t = 1; t <= imp.getNFrames(); ++t) {
      switch (job.model().normalizationType) {
      case 1: { // MIN/MAX
        float minValue = Float.POSITIVE_INFINITY;
        float maxValue = Float.NEGATIVE_INFINITY;
        for (int z = 1; z <= imp.getNSlices(); ++z) {
          for (int c = 1; c <= imp.getNChannels(); ++c) {
            if (job.interrupted())
                throw new InterruptedException("Aborted by user");
            job.setTaskProgress(
                "Computing data min/max (t=" + t + ", z=" + z +
                ", c=" + c + ")", imp.getStackIndex(c, z, t),
                imp.getImageStackSize());
            float[] values = (float[])
                imp.getStack().getPixels(imp.getStackIndex(c, z, t));
            for (int i = 0; i < imp.getHeight() * imp.getWidth(); ++i) {
              if (values[i] > maxValue) maxValue = values[i];
              if (values[i] < minValue) minValue = values[i];
            }
          }
        }
        scales[t - 1] = (float)(1.0 / (maxValue - minValue));
        offsets[t - 1] = -minValue;
        break;
      }
      case 2: { // Zero mean, unit standard deviation
        float sum = 0;
        for (int z = 1; z <= imp.getNSlices(); ++z) {
          for (int c = 1; c <= imp.getNChannels(); ++c) {
            if (job.interrupted())
                throw new InterruptedException("Aborted by user");
            job.setTaskProgress(
                "Computing data mean (t=" + t + ", z=" + z +
                ", c=" + c + ")", imp.getStackIndex(c, z, t),
                imp.getImageStackSize());
            float[] values = (float[])
                imp.getStack().getPixels(imp.getStackIndex(c, z, t));
            for (int i = 0; i < imp.getHeight() * imp.getWidth(); ++i)
                sum += values[i];
          }
        }
        offsets[t - 1] = -sum / (imp.getNSlices() * imp.getNChannels() *
                                 imp.getHeight() * imp.getWidth());
        sum = 0;
        for (int z = 1; z <= imp.getNSlices(); ++z) {
          for (int c = 1; c <= imp.getNChannels(); ++c) {
            if (job.interrupted())
                throw new InterruptedException("Aborted by user");
            job.setTaskProgress(
                "Computing data standard deviation (t=" + t + ", z=" + z +
                ", c=" + c + ")", imp.getStackIndex(c, z, t),
                imp.getImageStackSize());
            float[] values = (float[])
                imp.getStack().getPixels(imp.getStackIndex(c, z, t));
            for (int i = 0; i < imp.getHeight() * imp.getWidth(); ++i)
                sum += (values[i] + offsets[t - 1]) *
                    (values[i] + offsets[t - 1]);
          }
        }
        scales[t - 1] = (float)Math.sqrt(
            (imp.getNSlices() * imp.getNChannels() * imp.getHeight() *
             imp.getWidth()) / sum);
        break;
      }
      case 3: { // Max norm 1
        float maxSqrNorm = 0;
        for (int z = 1; z <= imp.getNSlices(); ++z) {
          float[] sqrNorm = new float[imp.getHeight() * imp.getWidth()];
          Arrays.fill(sqrNorm, 0);
          for (int c = 1; c <= imp.getNChannels(); ++c) {
            if (job.interrupted())
                throw new InterruptedException("Aborted by user");
            job.setTaskProgress(
                "Computing data norm (t=" + t + ", z=" + z +
                ", c=" + c + ")", imp.getStackIndex(c, z, t),
                imp.getImageStackSize());
            float[] values = (float[])
                imp.getStack().getPixels(imp.getStackIndex(c, z, t));
            for (int i = 0; i < imp.getHeight() * imp.getWidth(); ++i)
                sqrNorm[i] += values[i] * values[i];
          }
          for (int i = 0; i < imp.getHeight() * imp.getWidth(); ++i)
              if (sqrNorm[i] > maxSqrNorm) maxSqrNorm = sqrNorm[i];
        }
        offsets[t - 1] = 0;
        scales[t - 1] = (float)(1.0 / Math.sqrt(maxSqrNorm));
        break;
      }
      default:
        break;
      }

      IJ.log("t = " + t + ": scale = " + scales[t - 1] + ", offset = " +
             offsets[t - 1]);

      if (imp == out && (scales[t - 1] != 1 || offsets[t - 1] != 0)) {
        out = IJ.createHyperStack(
            imp.getTitle() + " - normalized", imp.getWidth(),
            imp.getHeight(), imp.getNChannels(), imp.getNSlices(),
            imp.getNFrames(), 32);
        out.setCalibration(imp.getCalibration().copy());

        // Special treatment for single image
        if (imp.getImageStackSize() == 1) {
          ImageProcessor ip = imp.getProcessor().duplicate();
          ip.add(offsets[t - 1]);
          ip.multiply(scales[t - 1]);
          out.setProcessor(ip);
          job.setTaskProgress(1, 1);
          return out;
        }

        for (int t2 = 1; t2 <= t; ++t2) {
          for (int z = 1; z <= imp.getNSlices(); ++z) {
            for (int c = 1; c <= imp.getNChannels(); ++c) {
              ImageProcessor ip =
                  imp.getStack().getProcessor(
                      imp.getStackIndex(c, z, t2)).duplicate();
              ip.add(offsets[t2 - 1]);
              ip.multiply(scales[t2 - 1]);
              out.getStack().setProcessor(
                  ip, out.getStackIndex(c, z, t2));
            }
          }
        }
      }
      else {
        for (int z = 1; z <= imp.getNSlices(); ++z) {
          for (int c = 1; c <= imp.getNChannels(); ++c) {
            ImageProcessor ip =
                imp.getStack().getProcessor(
                    imp.getStackIndex(c, z, t)).duplicate();
            ip.add(offsets[t - 1]);
            ip.multiply(scales[t - 1]);
            out.getStack().setProcessor(
                ip, out.getStackIndex(c, z, t));
          }
        }
      }
    }
    return out;
  }

/*======================================================================*/
/*!
 *   Convert the given ImagePlus to comply to the given unet model. If the
 *   unet model is 2D, both, time and z dimension are treated as time
 *   leading to slicewise segmentation. If the given ImagePlus already
 *   fulfills the requirements of the model it will be simply returned, so
 *   be careful to check whether the returned ImagePlus equals the given
 *   ImagePlus before performing unwanted destructive changes. If a new
 *   ImagePlus is created it will be initially hidden.
 *
 *   \param imp The ImagePlus to convert to unet model compliant format
 *   \param job The unet job asking for the resize operation. Its model
 *     will be queried for the required output format. Task progress will
 *     be reported via the UnetJob.
 *
 *   \exception InterruptedException The user can quit this operation
 *     resulting in this error
 *
 *   \return The unet compatible ImagePlus correponding to the input
 *     ImagePlus. If the input was already compatible a reference to
 *     the input is returned, otherwise a new ImagePlus created.
 */
/*======================================================================*/
  public static ImagePlus convertToUnetFormat(
      ImagePlus imp, UnetJob job, boolean keepOriginal)
      throws InterruptedException {
    ImagePlus out = normalizeValues(
        rescaleZ(
            rescaleXY(
                fixStackLayout(
                    convertToFloat(makeComposite(imp, job), job), job),
                ImageProcessor.BILINEAR, job),
            ImageProcessor.BILINEAR, job),
        job);

    if (imp != out) {
      if (!keepOriginal) {
        imp.changes = false;
        imp.close();
      }
      out.resetDisplayRange();
      out.show();
    }
    return out;
  }

  private static final float INF = 1e20f;

  private static float[] dt(float[] f, int n) {
    float[] d = new float[n];
    int[] v = new int[n];
    float[] z = new float[n + 1];
    int k = 0;
    v[0] = 0;
    z[0] = -INF;
    z[1] = INF;
    for (int q = 1; q <= n - 1; q++) {
      float s  = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) /
          (2 * q - 2 * v[k]);
      while (s <= z[k]) {
        k--;
        s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k]);
      }
      k++;
      v[k] = q;
      z[k] = s;
      z[k+1] = INF;
    }

    k = 0;
    for (int q = 0; q <= n - 1; q++) {
      while (z[k+1] < q) k++;
      d[q] = (q - v[k]) * (q - v[k]) + f[v[k]];
    }

    return d;
  }

  private static void dt(ImageProcessor ip) {
    int width = ip.getWidth();
    int height = ip.getHeight();
    float[] f = new float[Math.max(width, height)];

    // transform along columns
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) f[y] = ip.getf(x, y);
      float[] d = dt(f, height);
      for (int y = 0; y < height; y++) ip.setf(x, y, d[y]);
    }

    // transform along rows
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) f[x] = ip.getf(x, y);
      float[] d = dt(f, width);
      for (int x = 0; x < width; x++) ip.setf(x, y, d[x]);
    }
  }

  private static void distanceTransform(ImageProcessor ip, float fg) {
    for (int y = 0; y < ip.getHeight(); y++) {
      for (int x = 0; x < ip.getWidth(); x++) {
        if (ip.getf(x, y) == fg) ip.setf(x, y, 0);
        else ip.setf(x, y, INF);
      }
    }
    dt(ip);
    for (int y = 0; y < ip.getHeight(); y++) {
      for (int x = 0; x < ip.getWidth(); x++) {
        ip.setf(x, y, (float)Math.sqrt(ip.getf(x, y)));
      }
    }
  }

  private static void distanceTransformBG(ImageProcessor ip, float fg) {
    for (int y = 0; y < ip.getHeight(); y++) {
      for (int x = 0; x < ip.getWidth(); x++) {
        if (ip.getf(x, y) != fg) ip.setf(x, y, 0);
        else ip.setf(x, y, INF);
      }
    }
    dt(ip);
    for (int y = 0; y < ip.getHeight(); y++) {
      for (int x = 0; x < ip.getWidth(); x++) {
        ip.setf(x, y, (float)Math.sqrt(ip.getf(x, y)));
      }
    }
  }


/*======================================================================*/
/*!
 *   Extracts annotations stored as overlay from the given ImagePlus and
 *   generates corresponding labels and weights. Annotation color is used
 *   to distinguish the different classes, where magenta indicates ignore
 *   regions. Finetuning only works for 2D models at the moment because
 *   the annotation tools of ImageJ are restricted to 2D.
 *
 *   \param imp The ImagePlus to generate labels and weights for
 *   \param job Task progress will be reported via this UnetJob.
 *
 *   \exception InterruptedException The user can quit this operation
 *     resulting in this error
 *
 *   \return Three ImagePlus objects, the first containing the labels,
 *     the second the weights and the third the pdf to sample training
 *     examples from (Input to CreateDeformationLayer).
 */
/*======================================================================*/
  public static ImagePlus[] convertAnnotationsToLabelsAndWeights(
      ImagePlus imp, UnetJob job)
      throws InterruptedException, NotImplementedException {


    float foregroundBackgroundRatio = job.model().foregroundBackgroundRatio;
    float sigma1_px = job.model().sigma1Um / job.model().elementSizeUm[1];
    float borderWeightFactor = job.model().borderWeightFactor;
    float sigma2_px = job.model().borderWeightSigmaUm /
        job.model().elementSizeUm[1];

    ImagePlus[] blobs = new ImagePlus[3];

    // Fix layout of input ImagePlus and get dimensions
    blobs[2] = fixStackLayout(imp, job);
    int T = blobs[2].getNFrames();
    int Z = blobs[2].getNSlices();
    int W = blobs[2].getWidth();
    int H = blobs[2].getHeight();

    // Create output blobs
    blobs[0] = IJ.createHyperStack(
        imp.getTitle() + " - labels", W, H, 1, Z, T, 32);
    blobs[0].setCalibration(imp.getCalibration().copy());
    for (int t = 0; t < T; t++) {
      for (int z = 0; z < T; z++) {
        ImageStack stack = blobs[0].getStack();
        int stackIndex = blobs[0].getStackIndex(1, z + 1, t + 1);
        ImageProcessor ip = stack.getProcessor(stackIndex);
        ip.setValue(0.0);
        ip.fill();
      }
    }

    blobs[1] = IJ.createHyperStack(
        imp.getTitle() + " - weights", W, H, 1, Z, T, 32);
    blobs[1].setCalibration(imp.getCalibration().copy());
    for (int t = 0; t < T; t++) {
      for (int z = 0; z < T; z++) {
        ImageStack stack = blobs[1].getStack();
        int stackIndex = blobs[1].getStackIndex(1, z + 1, t + 1);
        ImageProcessor ip = stack.getProcessor(stackIndex);
        ip.setValue(0.5);
        ip.fill();
      }
    }

    blobs[2] = IJ.createHyperStack(
        imp.getTitle() + " - sample pdf", W, H, 1, Z, T, 32);
    blobs[2].setCalibration(imp.getCalibration().copy());
    for (int t = 0; t < T; t++) {
      for (int z = 0; z < T; z++) {
        ImageStack stack = blobs[2].getStack();
        int stackIndex = blobs[2].getStackIndex(1, z + 1, t + 1);
        ImageProcessor ip = stack.getProcessor(stackIndex);
        ip.setValue(foregroundBackgroundRatio);
        ip.fill();
      }
    }

    Roi[] rois = imp.getOverlay().toArray();
    int[] nObjects = new int[T];
    int nObjectsTotal = 0;
    Arrays.fill(nObjects, 0);
    for (Roi roi: rois) {
      int z = roi.getZPosition();
      int t = roi.getTPosition();
      int stackIndex = blobs[0].getStackIndex(1, z + 1, t + 1);
      ImageProcessor ipLabels = blobs[0].getStack().getProcessor(stackIndex);
      ImageProcessor ipWeights = blobs[1].getStack().getProcessor(stackIndex);
      ImageProcessor ipPdf = blobs[2].getStack().getProcessor(stackIndex);
      if (roi.getFillColor() != null &&
          roi.getFillColor().getRGB() == Color.magenta.getRGB()) {
        // Magenta indicates ignore regions
        ipWeights.setValue(0.0);
        ipWeights.fill(roi);
        ipPdf.setValue(0.0);
        ipPdf.fill(roi);
      }
      else {
        nObjects[t]++;
        nObjectsTotal++;
        ipLabels.setValue(nObjects[t]);
        ipLabels.fill(roi);
        ipWeights.setValue(1.0);
        ipWeights.fill(roi);
        ipPdf.setValue(1.0);
        ipPdf.fill(roi);
      }
    }

    blobs[0] = rescaleZ(
        rescaleXY(blobs[0], ImageProcessor.NEAREST_NEIGHBOR, job),
        ImageProcessor.NEAREST_NEIGHBOR, job);
    blobs[1] = rescaleZ(
        rescaleXY(blobs[1], ImageProcessor.BILINEAR, job),
        ImageProcessor.BILINEAR, job);
    blobs[2] = rescaleZ(
        rescaleXY(blobs[2], ImageProcessor.BILINEAR, job),
        ImageProcessor.BILINEAR, job);
    Z = blobs[0].getNSlices();
    W = blobs[0].getWidth();
    H = blobs[0].getHeight();

    int objIdx = 1;

    if (Z == 1) {

      // Piece of cake, just process slicewise :-D

      // Create background gaps
      for (int t = 0; t < T; ++t) {
        ImageProcessor ip, wp, w2p;
        if (blobs[0].getStack().getSize() == 1) {
          ip = blobs[0].getProcessor();
          wp = blobs[1].getProcessor();
          w2p = blobs[2].getProcessor();
        }
        else {
          ip = blobs[0].getStack().getProcessor(t);
          wp = blobs[1].getStack().getProcessor(t);
          w2p = blobs[2].getStack().getProcessor(t);
        }

        for (int y = 0; y < H; y++) {
          for (int x = 0; x < W; x++) {
            float val = ip.getf(x, y);
            for (int dy = -1; dy <= 0 && val != 0; dy++) {
              if (y + dy < 0 || y + dy >= H) continue;
              for (int dx = -1; dx <= 1 && val != 0; dx++) {
                if ((dy == 0 && dx > -1) || x + dx < 0 || x + dx >= W)
                    continue;
                float nbVal = ip.getf(x + dx, y + dy);
                if (nbVal == 0 || nbVal == val) continue;
                val = 0;
                ip.setf(x, y, val);
                wp.setf(x, y, 0.5f);
                w2p.setf(x, y, foregroundBackgroundRatio);
              }
            }
          }
        }

        // Compute extra weights
        ImageProcessor min1Dist = ip.duplicate();
        min1Dist.setValue(INF);
        min1Dist.fill();
        ImageProcessor min2Dist = ip.duplicate();
        min2Dist.setValue(INF);
        min2Dist.fill();
        for (int i = 1; i <= nObjects[t]; i++, objIdx++) {
          job.setTaskProgress(
              "Processing slice " + (t + 1) + " / " + T + ": object " + i +
              " / " + nObjects[t], objIdx, nObjectsTotal);
          ImageProcessor d = ip.duplicate();
          ImageProcessor newMin = ip.duplicate();
          distanceTransform(d, i);
          for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
              float min1dist = min1Dist.getf(x, y);
              float min2dist = Math.min(min2Dist.getf(x, y), d.getf(x, y));
              min1Dist.setf(x, y, Math.min(min1dist, min2dist));
              min2Dist.setf(x, y, Math.max(min1dist, min2dist));
            }
          }
        }
        float va = 1 - foregroundBackgroundRatio;
        for (int y = 0; y < H; y++) {
          for (int x = 0; x < W; x++) {
            if (wp.getf(x, y) == 0 || wp.getf(x, y) == 1) continue;
            float d1 = min1Dist.getf(x, y);
            float d2 = min2Dist.getf(x, y);
            float wa = (float)Math.exp(
                -(d1 * d1) / (2 * sigma1_px * sigma1_px));
            float we = (float)Math.exp(
                -(d1 + d2) * (d1 + d2) / (2 * sigma2_px * sigma2_px));
            wp.setf(x, y, borderWeightFactor * we + va * wa +
                    foregroundBackgroundRatio);
          }
        }

        // Binarize instance labels
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                ip.setf(x, y, ip.getf(x, y) > 0 ? 1 : 0);
      }
    }
    else {

      // Uh, real 3D data... what a mess :-(

      throw new NotImplementedException(
          "Sorry, 3D finetuning is not implemented yet");
    }

    return blobs;
  }

  private static void save2DBlob(
      ImagePlus imp, IHDF5Writer writer, String dsName, UnetJob job)
        throws InterruptedException {
    int T = imp.getNFrames();
    int Z = imp.getNSlices();
    int N = T * Z;
    int C = imp.getNChannels();
    int W = imp.getWidth();
    int H = imp.getHeight();
    long[] dims = { N, C, H, W };
    int[] blockDims = { 1, 1, H, W };
    long[] blockIdx = { 0, 0, 0, 0 };

    writer.float32().createMDArray(
        dsName, dims, blockDims, HDF5FloatStorageFeatures.createDeflation(3));

    for (int t = 0; t < T; ++t) {
      for (int z = 0; z < Z; ++z) {
        blockIdx[0] = t * Z + z;
        for (int c = 0; c < C; ++c) {
          if (job.interrupted())
              throw new InterruptedException("Aborted by user");
          blockIdx[1] = c;

          // Create HDF5 Multi-dimensional Array (memory space)
          MDFloatArray data = new MDFloatArray(blockDims);
          float[] dataFlat = data.getAsFlatArray();

          // Get IJ index to processed slice
          ImageStack stack = imp.getStack();
          int stackIndex = imp.getStackIndex(c + 1, z + 1, t + 1);

          System.arraycopy(stack.getPixels(stackIndex), 0, dataFlat, 0, H * W);

          job.setTaskProgress(
              "Saving t=" + t + ", z=" + z + ", c=" + c,
              imp.getStackIndex(c, z, t), imp.getImageStackSize());

          // save it
          writer.float32().writeMDArrayBlock(dsName, data, blockIdx);
        }
      }
    }
  }

  private static void save3DBlob(
      ImagePlus imp, IHDF5Writer writer, String dsName, UnetJob job)
        throws InterruptedException {
    int T = imp.getNFrames();
    int Z = imp.getNSlices();
    int C = imp.getNChannels();
    int W = imp.getWidth();
    int H = imp.getHeight();
    long[] dims = { T, C, Z, H, W };
    int[] blockDims = { 1, 1, 1, H, W };
    long[] blockIdx = { 0, 0, 0, 0, 0 };

    writer.float32().createMDArray(
        dsName, dims, blockDims, HDF5FloatStorageFeatures.createDeflation(3));

    for (int t = 0; t < T; ++t) {
      blockIdx[0] = t;
      for (int z = 0; z < Z; ++z) {
        blockIdx[2] = z;
        for (int c = 0; c < C; ++c) {
          blockIdx[1] = c;
          if (job.interrupted())
              throw new InterruptedException("Aborted by user");

          // Create HDF5 Multi-dimensional Array (memory space)
          MDFloatArray data = new MDFloatArray(blockDims);
          float[] dataFlat = data.getAsFlatArray();

          // Get IJ index to processed slice
          ImageStack stack = imp.getStack();
          int stackIndex = imp.getStackIndex(c + 1, z + 1, t + 1);

          System.arraycopy(stack.getPixels(stackIndex), 0, dataFlat, 0, H * W);

          job.setTaskProgress(
              "Saving t=" + t + ", z=" + z + ", c=" + c,
              imp.getStackIndex(c, z, t), imp.getImageStackSize());

          // save it
          writer.float32().writeMDArrayBlock(dsName, data, blockIdx);
        }
      }
    }
  }

  public static ImagePlus saveHDF5Blob(
      ImagePlus imp, File outFile, UnetJob job, boolean keepOriginal)
      throws IOException, InterruptedException, NotImplementedException {
    if (job == null) {
      IJ.error("Cannot save HDF5 blob without associated unet model");
      throw new InterruptedException("No active unet job");
    }

    String dsName = job.model().inputDatasetName;

    IJ.log("  Processing '" + imp.getTitle() + "': " +
           "#frames = " + imp.getNFrames() +
           ", #slices = " + imp.getNSlices() +
           ", #channels = " + imp.getNChannels() +
           ", height = " + imp.getHeight() +
           ", width = " + imp.getWidth() +
           " with element size = [" +
           imp.getCalibration().pixelDepth + ", " +
           imp.getCalibration().pixelHeight + ", " +
           imp.getCalibration().pixelWidth + "]");

    ImagePlus impScaled = convertToUnetFormat(imp, job, keepOriginal);

    // Recursively create parent folders
    Vector<File> createdFolders = new Vector<File>();
    File folder = outFile.getParentFile();
    while (folder != null && !folder.isDirectory()) {
      createdFolders.add(folder);
      folder = folder.getParentFile();
    }
    for (int i = createdFolders.size() - 1; i >= 0; --i) {
      if (job.interrupted())
          throw new InterruptedException("Aborted by user");
      job.setTaskProgress(
          "Creating folder '" + createdFolders.get(i) + "'", 0, 0);
      IJ.log("$ mkdir \"" + createdFolders.get(i) + "\"");
      if (!createdFolders.get(i).mkdir())
          throw new IOException(
              "Could not create folder '" +
              createdFolders.get(i).getAbsolutePath() + "'");
      createdFolders.get(i).deleteOnExit();
      job.setTaskProgress(1, 1);
    }

    // syncMode: Always wait on close and flush till all data is written!
    // useSimpleDataSpace: Save attributes as plain vectors
    IHDF5Writer writer =
        HDF5Factory.configure(outFile.getAbsolutePath()).syncMode(
            IHDF5WriterConfigurator.SyncMode.SYNC_BLOCK)
        .useSimpleDataSpaceForAttributes().overwrite().writer();
    outFile.deleteOnExit();

    if (job.model().elementSizeUm.length == 2)
        save2DBlob(impScaled, writer, dsName, job);
    else save3DBlob(impScaled, writer, dsName, job);

    if (job instanceof UnetFinetuneJob && imp.getOverlay() != null) {
      ImagePlus[] blobs = convertAnnotationsToLabelsAndWeights(imp, job);

      if (job.model().elementSizeUm.length == 2) {
        save2DBlob(blobs[0], writer, "labels", job);
        save2DBlob(blobs[1], writer, "weights", job);
        save2DBlob(blobs[2], writer, "weights2", job);
      }
      else {
        save3DBlob(blobs[0], writer, "labels", job);
        save3DBlob(blobs[1], writer, "weights", job);
        save3DBlob(blobs[2], writer, "weights2", job);
      }
    }

    writer.file().close();
    job.setTaskProgress(1, 1);
    IJ.log("ImagePlus converted to caffe blob and saved to '" +
           outFile.getAbsolutePath() + "'");

    return impScaled;
  }

  public static ProcessResult execute(Vector<String> command, UnetJob job)
      throws IOException, InterruptedException {
    if (command == null || command.size() == 0)
        throw new IOException(
            "UnetTools.execute() Received empty command");
    String cmdString = command.get(0);
    for (int i = 1; i < command.size(); ++i)
        cmdString += " " + command.get(i);
    if (job != null) job.setTaskProgress(
        "Executing '" + cmdString + "'", 0, 0);
    else IJ.showStatus("Executing '" + cmdString + "'");
    IJ.log("$ " + cmdString);

    Process p = new ProcessBuilder(command).start();

    BufferedReader stdOutput =
        new BufferedReader(new InputStreamReader(p.getInputStream()));
    BufferedReader stdError =
        new BufferedReader(new InputStreamReader(p.getErrorStream()));

    ProcessResult res = new ProcessResult();

    while (true) {
      try {
        // Read output on the fly to avoid BufferedReader overflow
        while (stdOutput.ready())
            res.cout += stdOutput.readLine() + "\n";
        while (stdError.ready()) res.cerr += stdError.readLine() + "\n";

        res.exitStatus = p.exitValue();

        // Read residual output after process finished
        while (stdOutput.ready())
            res.cout += stdOutput.readLine() + "\n";
        while (stdError.ready()) res.cerr += stdError.readLine() + "\n";
        if (job != null) job.setTaskProgress(1, 1);
        return res;
      }
      catch (IllegalThreadStateException e) {}
      Thread.sleep(100);
    }
  }

  public static ProcessResult execute(
      String command, Session session, UnetJob job)
      throws JSchException, InterruptedException, IOException {
    if (job != null)
        job.setTaskProgress(
            "Executing remote command '" + command + "'", 0, 0);
    else IJ.showStatus("Executing remote command '" + command + "'");
    IJ.log(session.getUserName() + "@" + session.getHost() +
           "$ " + command);

    Channel channel = session.openChannel("exec");
    ((ChannelExec)channel).setCommand(command);

    InputStream stdOutput = channel.getInputStream();
    InputStream stdError = ((ChannelExec)channel).getErrStream();

    byte[] buf = new byte[1024];

    ProcessResult res = new ProcessResult();

    channel.connect();
    while (true) {
      while(stdOutput.available() > 0) {
        int i = stdOutput.read(buf, 0, 1024);
        if (i < 0) break;
        res.cout += new String(buf, 0, i);
      }
      while(stdError.available() > 0) {
        int i = stdError.read(buf, 0, 1024);
        if (i < 0) break;
        res.cerr += new String(buf, 0, i);
      }
      if (channel.isClosed()) {
        if (stdOutput.available() > 0 || stdError.available() > 0) continue;
        res.exitStatus = channel.getExitStatus();
        if (job != null) job.setTaskProgress(1, 1);
        return res;
      }
      Thread.sleep(100);
    }
  }

  public static ModelDefinition loadModel(File modelFile) throws HDF5Exception {
    if (!modelFile.exists()) return null;
    ModelDefinition model = new ModelDefinition();
    model.file = modelFile;
    model.load();
    return model;
  }

  public static void loadSegmentationToImagePlus(
      File file, UnetSegmentationJob job, boolean outputScores)
      throws HDF5Exception, Exception {
    IHDF5Reader reader =
        HDF5Factory.configureForReading(file.getAbsolutePath()).reader();
    List<String> outputs = reader.getGroupMembers("/");
    for (String dsName : outputs) {
      IJ.showStatus("Creating visualization for output " + dsName);

      HDF5DataSetInformation dsInfo =
          reader.object().getDataSetInformation(dsName);
      boolean is2D = dsInfo.getDimensions().length == 4;

      String title = dsName;
      int nFrames, nChannels, nLevs, nRows, nCols;

      title = job.imageName() + " - " + title;
      nFrames   = (int)dsInfo.getDimensions()[0];
      nChannels = (int)dsInfo.getDimensions()[1];
      nLevs     = (is2D) ? 1 : (int)dsInfo.getDimensions()[2];
      nRows     = (int)dsInfo.getDimensions()[2 + ((is2D) ? 0 : 1)];
      nCols     = (int)dsInfo.getDimensions()[3 + ((is2D) ? 0 : 1)];

      ImagePlus impNew;
      if (outputScores)
          impNew = IJ.createHyperStack(
              title, nCols, nRows, nChannels, nLevs, nFrames, 32);
      else
          impNew = IJ.createHyperStack(
              title, nCols, nRows, 1, nLevs, nFrames, 16);

      impNew.setDisplayMode(IJ.GRAYSCALE);
      impNew.getCalibration().pixelDepth =
          job.imageCalibration().pixelDepth;
      impNew.getCalibration().pixelHeight =
          job.imageCalibration().pixelHeight;
      impNew.getCalibration().pixelWidth =
          job.imageCalibration().pixelWidth;
      impNew.getCalibration().setUnit("um");

      if (is2D) {
        int[] blockDims = { 1, 1, nRows, nCols };
        long[] blockIdx = { 0, 0, 0, 0 };

        for (int t = 0; t < nFrames; ++t) {
          blockIdx[0] = t;
          if (outputScores) {
            for (int c = 0; c < nChannels; ++c) {
              blockIdx[1] = c;
              IJ.showProgress(t * nChannels + c + 1, nFrames * nChannels);
              MDFloatArray data = reader.float32().readMDArrayBlock(
                  dsName, blockDims, blockIdx);
              ImageProcessor ip = impNew.getStack().getProcessor(
                  impNew.getStackIndex(c + 1, 1, t + 1));
              System.arraycopy(
                  data.getAsFlatArray(), 0, (float[])ip.getPixels(), 0,
                  nRows * nCols);
            }
          }
          else {
            float[] maxScore = new float[nRows * nCols];
            short[] maxIndex = (short[]) impNew.getStack().getProcessor(
                impNew.getStackIndex(1, 1, t + 1)).getPixels();
            int c = 0;
            blockIdx[1] = c;
            IJ.showProgress(t * nChannels + c + 1, nFrames * nChannels);
            float[] score = reader.float32().readMDArrayBlock(
                dsName, blockDims, blockIdx).getAsFlatArray();
            System.arraycopy(score, 0, maxScore, 0, nRows * nCols);
            Arrays.fill(maxIndex, (short) c);
            ++c;
            for (; c < nChannels; ++c) {
              blockIdx[1] = c;
              IJ.showProgress(t * nChannels + c + 1, nFrames * nChannels);
              score = reader.float32().readMDArrayBlock(
                  dsName, blockDims, blockIdx).getAsFlatArray();
              for (int i = 0; i < nRows * nCols; ++i) {
                if (score[i] > maxScore[i]) {
                  maxScore[i] = score[i];
                  maxIndex[i] = (short) c;
                }
              }
            }
          }
        }
      }
      else {
        int[] blockDims = { 1, 1, 1, nRows, nCols };
        long[] blockIdx = { 0, 0, 0, 0, 0 };

        for (int t = 0; t < nFrames; ++t) {
          blockIdx[0] = t;
          for (int z = 0; z < nLevs; ++z) {
            blockIdx[2] = z;
            if (outputScores) {
              for (int c = 0; c < nChannels; ++c) {
                blockIdx[1] = c;
                IJ.showProgress(
                    (t * nLevs + z) * nChannels + c + 1,
                    nFrames * nLevs * nChannels);
                MDFloatArray data =
                    reader.float32().readMDArrayBlock(
                        dsName, blockDims, blockIdx);
                ImageProcessor ip = impNew.getStack().getProcessor(
                    impNew.getStackIndex(c + 1, z + 1, t + 1));
                System.arraycopy(
                    data.getAsFlatArray(), 0, (float[])ip.getPixels(), 0,
                    nRows * nCols);
              }
            }
            else {
              float[] maxScore = new float[nRows * nCols];
              short[] maxIndex = (short[]) impNew.getStack().getProcessor(
                  impNew.getStackIndex(1, z + 1, t + 1)).getPixels();
              int c = 0;
              blockIdx[1] = c;
              IJ.showProgress(
                  (t * nLevs + z) * nChannels + c + 1,
                  nFrames * nLevs * nChannels);
              float[] score = reader.float32().readMDArrayBlock(
                  dsName, blockDims, blockIdx).getAsFlatArray();
              System.arraycopy(
                  score, 0, maxScore, 0, nRows * nCols);
              Arrays.fill(maxIndex, (short) c);
              ++c;
              for (; c < nChannels; ++c) {
                blockIdx[1] = c;
                IJ.showProgress(
                    (t * nLevs + z) * nChannels + c + 1,
                    nFrames * nLevs * nChannels);
                score = reader.float32().readMDArrayBlock(
                    dsName, blockDims, blockIdx).getAsFlatArray();
                for (int i = 0; i < nRows * nCols; ++i) {
                  if (score[i] > maxScore[i]) {
                    maxScore[i] = score[i];
                    maxIndex[i] = (short) c;
                  }
                }
              }
            }
          }
        }
      }
      if (outputScores) impNew.setC(1);
      impNew.resetDisplayRange();
      impNew.show();
    }
    reader.close();
  }

  static int checkAck(InputStream in) throws IOException {
    int b = in.read();
    // b may be 0 for success,
    //          1 for error,
    //          2 for fatal error,
    //          -1
    if (b == 0) return b;
    if (b == -1) return b;

    if (b == 1 || b == 2) {
      StringBuffer sb = new StringBuffer();
      int c;
      do {
        c = in.read();
        sb.append((char)c);
      }
      while (c != '\n');
      if (b == 1) IJ.log(sb.toString());
      if (b == 2) IJ.log(sb.toString());
    }
    return b;
  }

}