

package org.orego.app.face3dActivity.model3D.services.wavefront;

import android.util.Log;

import org.orego.app.face3dActivity.model3D.services.wavefront.materials.Materials;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.StringTokenizer;

import utils.Tuple;

public final class WavefrontLoader {

    private static final float DUMMY_Z_TEXTURE_COORDINATE = -5.0f;

    static final boolean INDEXES_START_AT_1 = true;

    private boolean hasTextureCoordinates3D = false;

    private ArrayList<Tuple> textureCoordinates;

    private Faces faces; // model faces
    private FaceMaterials faceMats; // materials used by faces
    private Materials materials; // materials defined in MTL file
    private ModelDimensions modelDims; // model dimensions

    // metadata
    private int numVerts = 0;
    private int numTextures = 0;
    private int numNormals = 0;
    private int numFaces = 0;

    // buffers
    private FloatBuffer vertsBuffer;
    private FloatBuffer normalsBuffer;
    private FloatBuffer colorVerts;
    private FloatBuffer textureCoordsBuffer;

    public WavefrontLoader() {
        textureCoordinates = new ArrayList<>();
        faceMats = new FaceMaterials();
        modelDims = new ModelDimensions();
    }

    public void analyzeModel(InputStream is) {
        int lineNum = 0;
        String line;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(is));

            while ((line = br.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.length() > 0) {

                    if (line.startsWith("v ")) { // vertex
                        numVerts++;
                    } else if (line.startsWith("vt")) { // tex coord
                        numTextures++;
                    } else if (line.startsWith("vn")) {// normal
                        numNormals++;
                    } else if (line.startsWith("f ")) { // face
                        final int faceSize;
                        if (line.contains("  ")) {
                            faceSize = line.split(" +").length - 1;
                        } else {
                            faceSize = line.split(" ").length - 1;
                        }
                        numFaces += (faceSize - 2);
                        // (faceSize-2)x3 = converting polygon to triangles
                    } else if (line.startsWith("mtllib ")) // build material
                    {
                        materials = new Materials(line.substring(7));
                    } else
                        System.out.println("Ignoring line " + lineNum + " : " + line);
                }
            }
        } catch (IOException e) {
            Log.e("WavefrontLoader", "Problem reading line '" + (++lineNum) + "'");
            Log.e("WavefrontLoader", e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    Log.e("WavefrontLoader", e.getMessage(), e);
                }
            }
        }

        Log.i("WavefrontLoader", "Number of vertices:" + numVerts);
        Log.i("WavefrontLoader", "Number of faces:" + numFaces);
    }


    public void allocateBuffers() {
        // size = 3 (x,y,z) * 4 (bytes per float)
        vertsBuffer = createNativeByteBuffer(numVerts * 3 * 4).asFloatBuffer();
        colorVerts = createNativeByteBuffer(numVerts * 3 * 4).asFloatBuffer();
        if (numNormals > 0) {
            normalsBuffer = createNativeByteBuffer(numNormals * 3 * 4).asFloatBuffer();
        }
        textureCoordsBuffer = createNativeByteBuffer(numTextures * 3 * 4).asFloatBuffer();
        if (numFaces > 0) {
            IntBuffer buffer = createNativeByteBuffer(numFaces * 3 * 4).asIntBuffer();
            faces = new Faces(numFaces, buffer);
        }
    }

    public void loadModel(InputStream is) {
        // String fnm = MODEL_DIR + modelNm + ".obj";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(is));
            readModel(br);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    private static ByteBuffer createNativeByteBuffer(int length) {
        // initialize vertex byte buffer for shape coordinates
        ByteBuffer bb = ByteBuffer.allocateDirect(length);
        // use the device hardware's native byte order
        bb.order(ByteOrder.nativeOrder());
        return bb;
    }

    private void readModel(BufferedReader br)
    // parse the OBJ file line-by-line
    {
        boolean isLoaded = true; // hope things will go okay

        int lineNum = 0;
        String line;
        boolean isFirstCoord = true;
        boolean isFirstTC = true;
        int numFaces = 0;

        int vertNumber = 0;
        int normalNumber = 0;


        try {
            while (((line = br.readLine()) != null)) {
                lineNum++;
                line = line.trim();
                if (line.length() > 0) {

                    if (line.startsWith("v ")) { // vertex
                        isLoaded = addVert(vertsBuffer, colorVerts, vertNumber++ * 3, line, isFirstCoord, modelDims) && isLoaded;
                        if (isFirstCoord)
                            isFirstCoord = false;
                    } else if (line.startsWith("vt")) { // tex coord
                        isLoaded = addTexCoord(line, isFirstTC) && isLoaded;
                        if (isFirstTC)
                            isFirstTC = false;
                    } else if (line.startsWith("vn")) // normal
                        isLoaded = addVert(normalsBuffer, colorVerts, normalNumber++ * 3, line, isFirstCoord, null) && isLoaded;
                    else if (line.startsWith("f ")) { // face
                        isLoaded = faces.addFace(line) && isLoaded;
                        numFaces++;
                    } else if (line.startsWith("usemtl ")) // use material
                        faceMats.addUse(numFaces, line.substring(7));
                    else
                        System.out.println("Ignoring line " + lineNum + " : " + line);
                }
            }
        } catch (IOException e) {
            Log.e("WavefrontLoader", e.getMessage(), e);
            throw new RuntimeException(e);
        }

        if (!isLoaded) {
            Log.e("WavefrontLoader", "Error loading model");
            // throw new RuntimeException("Error loading model");
        }
    } // end of readModel()

    private boolean addVert(FloatBuffer buffer, FloatBuffer colorsBuffer, int offset, String line, boolean isFirstCoord, ModelDimensions dimensions)
    /*
     * Add vertex from line "v x y z R G B" to vert ArrayList, and update the model dimension's info.
	 */ {
        float x = 0, y = 0, z = 0, r = 0, g = 0, b = 0;
        try {
            String[] tokens;
            if (line.contains("  ")) {
                tokens = line.split(" +");
            } else {
                tokens = line.split(" ");
            }
            x = Float.parseFloat(tokens[1]);
            y = Float.parseFloat(tokens[2]);
            z = Float.parseFloat(tokens[3]);
            if (tokens.length > 4) {
                r = Float.parseFloat(tokens[4]);
                g = Float.parseFloat(tokens[5]);
                b = Float.parseFloat(tokens[6]);
            }
            if (dimensions != null) {
                if (isFirstCoord)
                    modelDims.set(x, y, z);
                else
                    modelDims.update(x, y, z);
            }
            return true;

        } catch (NumberFormatException ex) {
            Log.e("WavefrontLoader", ex.getMessage());
        } finally {
            // try to build even with errors
            buffer.put(offset, x).put(offset + 1, y).put(offset + 2, z);
            colorsBuffer.put(offset, r).put(offset + 1, g).put(offset + 2, b);
        }

        return false;
    } // end of addVert()

    private boolean addTexCoord(String line, boolean isFirstTC)
    /*
     * Add the texture coordinate from the line "vt u v w" to the textureCoordinates ArrayList. There may only be two tex coords
	 * on the line, which is determined by looking at the first tex coord line.
	 */ {
        if (isFirstTC) {
            hasTextureCoordinates3D = checkTC3D(line);
            System.out.println("Using 3D tex coords: " + hasTextureCoordinates3D);
        }

        Tuple texCoord = readTCTuple(line);
        if (texCoord != null) {
            textureCoordinates.add(texCoord);
            return true;
        }

        return false;
    } // end of addTexCoord()

    private boolean checkTC3D(String line)
    /*
     * Check if the line has 4 tokens, which will be the "vt" token and 3 tex coords in this case.
	 */ {
        String[] tokens = line.split("\\s+");
        return (tokens.length == 4);
    } // end of checkTC3D()

    private Tuple<Float, Float, Float> readTCTuple(String line)
    /*
     * The line starts with a "vt" OBJ word and two or three floats (x, y, z) for the tex coords separated by spaces. If
	 * there are only two coords, then the z-value is assigned a dummy value, DUMMY_Z_TEXTURE_COORDINATE.
	 */ {
        StringTokenizer tokens = new StringTokenizer(line, " ");
        tokens.nextToken(); // skip "vt" OBJ word

        try {
            float x = Float.parseFloat(tokens.nextToken());
            float y = Float.parseFloat(tokens.nextToken());

            float z = DUMMY_Z_TEXTURE_COORDINATE;
            if (hasTextureCoordinates3D)
                z = Float.parseFloat(tokens.nextToken());
            return new Tuple<>(x, y, z);
        } catch (NumberFormatException e) {
            System.out.println(e.getMessage());
        }

        return null; // means an error occurred
    } // end of readTCTuple()

    public void reportOnModel() {
        Log.i("WavefrontLoader", "No. of vertices: " + vertsBuffer.capacity() / 3);
        Log.i("WavefrontLoader", "No. of normal coords: " + numNormals);
        Log.i("WavefrontLoader", "No. of tex coords: " + textureCoordinates.size());
        Log.i("WavefrontLoader", "No. of faces: " + numFaces);

        modelDims.reportDimensions();
        // dimensions of model (before centering and scaling)

        if (materials != null)
            materials.showMaterials(); // list defined materials
        faceMats.showUsedMaterials(); // show what materials have been used by
        // faces
    } // end of reportOnModel()

    public FloatBuffer getColorsVert() {
        return colorVerts;
    }

    public FloatBuffer getVerts() {
        return vertsBuffer;
    }

    public FloatBuffer getNormals() {
        return normalsBuffer;
    }

    public ArrayList<Tuple> getTextureCoordinates() {
        return textureCoordinates;
    }

    public Faces getFaces() {
        return faces;
    }

    public FaceMaterials getFaceMats() {
        return faceMats;
    }

    public Materials getMaterials() {
        return materials;
    }

    public ModelDimensions getDimensions() {
        return modelDims;
    }

} // end of WavefrontLoader class